package org.sagebionetworks.table.worker;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;
import static org.sagebionetworks.repo.model.table.TableConstants.ROW_ETAG;
import static org.sagebionetworks.repo.model.table.TableConstants.ROW_ID;
import static org.sagebionetworks.repo.model.table.TableConstants.ROW_VERSION;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.sagebionetworks.AsynchronousJobWorkerHelper;
import org.sagebionetworks.StackConfiguration;
import org.sagebionetworks.aws.SynapseS3Client;
import org.sagebionetworks.repo.manager.EntityAclManager;
import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.SemaphoreManager;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.asynch.AsynchJobStatusManager;
import org.sagebionetworks.repo.manager.asynch.AsynchJobUtils;
import org.sagebionetworks.repo.manager.table.ColumnModelManager;
import org.sagebionetworks.repo.manager.table.TableEntityManager;
import org.sagebionetworks.repo.manager.table.TableQueryManager;
import org.sagebionetworks.repo.manager.table.TableTransactionManager;
import org.sagebionetworks.repo.manager.table.TableViewManager;
import org.sagebionetworks.repo.manager.table.metadata.DefaultColumnModelMapper;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlList;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.DataType;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.ResourceAccess;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.asynch.AsynchJobState;
import org.sagebionetworks.repo.model.asynch.AsynchronousJobStatus;
import org.sagebionetworks.repo.model.dbo.dao.table.CSVToRowIterator;
import org.sagebionetworks.repo.model.dbo.dao.table.TableModelTestUtils;
import org.sagebionetworks.repo.model.dbo.file.FileHandleDao;
import org.sagebionetworks.repo.model.file.S3FileHandle;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.DownloadFromTableRequest;
import org.sagebionetworks.repo.model.table.DownloadFromTableResult;
import org.sagebionetworks.repo.model.table.EntityView;
import org.sagebionetworks.repo.model.table.ObjectField;
import org.sagebionetworks.repo.model.table.Query;
import org.sagebionetworks.repo.model.table.QueryOptions;
import org.sagebionetworks.repo.model.table.RowReferenceSet;
import org.sagebionetworks.repo.model.table.RowSet;
import org.sagebionetworks.repo.model.table.SortDirection;
import org.sagebionetworks.repo.model.table.SortItem;
import org.sagebionetworks.repo.model.table.TableUnavailableException;
import org.sagebionetworks.repo.model.table.ViewEntityType;
import org.sagebionetworks.repo.model.table.ViewObjectType;
import org.sagebionetworks.repo.model.table.ViewScope;
import org.sagebionetworks.repo.model.table.ViewType;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.table.cluster.utils.CSVUtils;
import org.sagebionetworks.table.cluster.utils.TableModelUtils;
import org.sagebionetworks.util.progress.ProgressCallback;
import org.sagebionetworks.workers.util.semaphore.LockUnavilableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.amazonaws.services.s3.model.GetObjectRequest;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import au.com.bytecode.opencsv.CSVReader;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class TableCSVDownloadWorkerIntegrationTest {

	// This test can be slow when run from outside of Amazon.
	public static final int MAX_WAIT_MS = 1000 * 60;
	
	@Autowired
	AsynchJobStatusManager asynchJobStatusManager;
	@Autowired
	StackConfiguration config;
	@Autowired
	FileHandleDao fileHandleDao;
	@Autowired
	EntityManager entityManager;
	@Autowired
	TableEntityManager tableEntityManager;
	@Autowired
	TableQueryManager tableQueryManger;
	@Autowired
	ColumnModelManager columnManager;
	@Autowired
	UserManager userManager;
	@Autowired
	SynapseS3Client s3Client;
	@Autowired
	SemaphoreManager semphoreManager;
	@Autowired
	TableViewManager tableViewManager;
	@Autowired
	TableTransactionManager transactionManager;
	@Autowired
	DefaultColumnModelMapper columnModelMapper;
	@Autowired
	private EntityAclManager entityAclManager;

	@Autowired
	private AsynchronousJobWorkerHelper asyncHelper;
	
	private UserInfo adminUserInfo;
	private UserInfo anonymousUser;
	RowReferenceSet referenceSet;
	List<ColumnModel> schema;
	List<String> headers;
	private String tableId;
	private List<String> toDelete;
	S3FileHandle fileHandle;
	ProgressCallback mockProgressCallback;
	
	@Before
	public void before() throws NotFoundException{
		semphoreManager.releaseAllLocksAsAdmin(new UserInfo(true));
		mockProgressCallback = Mockito.mock(ProgressCallback.class);
		when(mockProgressCallback.getLockTimeoutSeconds()).thenReturn(2L);
		// Start with an empty queue.
		asynchJobStatusManager.emptyAllQueues();
		// Get the admin user
		adminUserInfo = userManager.getUserInfo(BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId());
		anonymousUser = userManager.getUserInfo(BOOTSTRAP_PRINCIPAL.ANONYMOUS_USER.getPrincipalId());
		toDelete = new LinkedList<String>();
	}
	
	@After
	public void after(){
		if(adminUserInfo != null){
			for(String id: toDelete){
				try {
					entityManager.deleteEntity(adminUserInfo, id);
				} catch (Exception e) {}
			}
		}
		if(fileHandle != null){
			s3Client.deleteObject(fileHandle.getBucketName(), fileHandle.getKey());
			fileHandleDao.delete(fileHandle.getId());
		}
	}

	@Test
	public void testRoundTrip() throws Throwable{
		List<String[]> input = createTable();
		
		String sql = "select * from "+tableId;
		// Wait for the table to be ready
		RowSet result = waitForConsistentQuery(adminUserInfo, sql);
		assertNotNull(result);
		// Now download the data from this table as a csv
		DownloadFromTableRequest request = new DownloadFromTableRequest();
		request.setSql(sql);
		request.setWriteHeader(true);
		request.setIncludeRowIdAndRowVersion(true);
		List<String[]> results = downloadCSV(adminUserInfo, request);
		checkResults(results, input, true);
	}

	@Test
	public void testRoundTripWithCustomFileName() throws Throwable{
		List<String[]> input = createTable();

		String customFileName = "file1234";
		String sql = "select * from "+tableId;
		// Wait for the table to be ready
		RowSet result = waitForConsistentQuery(adminUserInfo, sql);
		assertNotNull(result);
		// Now download the data from this table as a csv
		DownloadFromTableRequest request = new DownloadFromTableRequest();
		request.setSql(sql);
		request.setWriteHeader(true);
		request.setIncludeRowIdAndRowVersion(true);
		request.setFileName(customFileName);

		// Call under test
		List<String[]> results = downloadCSV(adminUserInfo, request);

		checkResults(results, input, true);
	}

	// Test to reproduce PLFM-7050
	@Test
	public void testRoundTripWithAnonymousUserAndOpenData() throws Throwable {
		List<String[]> input = createTable();
		
		String sql = "select * from "+tableId;
		
		DownloadFromTableRequest request = new DownloadFromTableRequest();
		request.setSql(sql);
		request.setWriteHeader(true);
		request.setIncludeRowIdAndRowVersion(true);
		
		assertThrows(UnauthorizedException.class, () -> {
			downloadCSV(anonymousUser, request);
		});
				
		// Now set the table as open data
		entityManager.changeEntityDataType(adminUserInfo, tableId, DataType.OPEN_DATA);		
		
		// Grants read access to the public group		
		AccessControlList acl = entityAclManager.getACL(tableId, adminUserInfo);
		
		Set<ResourceAccess> access = new HashSet<ResourceAccess>(acl.getResourceAccess());
		
		access.add(new ResourceAccess()
				.setPrincipalId(BOOTSTRAP_PRINCIPAL.PUBLIC_GROUP.getPrincipalId())
				.setAccessType(Sets.newHashSet(ACCESS_TYPE.READ)));
		
		acl.setResourceAccess(access);
		
		entityAclManager.updateACL(acl, adminUserInfo);
		
		List<String[]> results = downloadCSV(anonymousUser, request);
		checkResults(results, input, true);
	}

	@Test
	public void testRoundTripSorted() throws Throwable{
		List<String[]> input = createTable();
		
		String sql = "select * from "+tableId;
		// Wait for the table to be ready
		RowSet result = waitForConsistentQuery(adminUserInfo, sql);
		assertNotNull(result);
		// Now download the data from this table as a csv
		DownloadFromTableRequest request = new DownloadFromTableRequest();
		request.setSql(sql);
		request.setWriteHeader(true);
		request.setIncludeRowIdAndRowVersion(true);
		SortItem sortItem = new SortItem();
		sortItem.setColumn("c");
		sortItem.setDirection(SortDirection.DESC);
		request.setSort(Lists.newArrayList(sortItem));
		List<String[]> results = downloadCSV(adminUserInfo, request);
		input = Lists.newArrayList(input.get(0), input.get(4), input.get(2), input.get(1), input.get(3));
		checkResults(results, input, true);
	}

	@Test
	public void testRoundTripWithZeroResults() throws Throwable {
		createTable();

		String sql = "select * from " + tableId + " where a = 'xxxxxx'";
		// Wait for the table to be ready
		RowSet result = waitForConsistentQuery(adminUserInfo, sql);
		assertNotNull(result);
		// Now download the data from this table as a csv
		DownloadFromTableRequest request = new DownloadFromTableRequest();
		request.setSql(sql);
		request.setWriteHeader(true);
		request.setIncludeRowIdAndRowVersion(true);
		List<String[]> results = downloadCSV(adminUserInfo, request);
		checkResults(results, Lists.<String[]> newArrayList(new String[] { "a", "b", "c" }), true);
	}
	
	@Test
	public void testDownloadWitoutWaitForSql() throws Throwable {
		List<String[]> input = createTable();

		String sql = "select * from " + tableId;
		// download the data from this table as a csv
		DownloadFromTableRequest request = new DownloadFromTableRequest();
		request.setSql(sql);
		request.setWriteHeader(true);
		request.setIncludeRowIdAndRowVersion(false);
		List<String[]> results = downloadCSV(adminUserInfo, request);
		checkResults(results, input, false);
	}
	
	@Test
	public void testDownloadViewWithoutEtag() throws Throwable{
		// Create a project view to query
		EntityView projectView =  createProjectView();
		// CSV download from the view
		DownloadFromTableRequest request = new DownloadFromTableRequest();
		request.setSql("select * from "+projectView.getId());
		request.setWriteHeader(true);
		request.setIncludeRowIdAndRowVersion(true);
		// null should default to false
		request.setIncludeEntityEtag(null);
		List<String[]> results = downloadCSV(adminUserInfo, request);
		assertEquals(4, results.size());
		String[] headers = results.get(0);
		String headerString = Arrays.toString(headers);
		String[] expected = new String[]{ROW_ID, ROW_VERSION, ObjectField.name.name()};
		String expectedString = Arrays.toString(expected);
		assertEquals(expectedString, headerString);
	}
	
	@Test
	public void testDownloadViewWithEtag() throws Throwable{
		// Create a project view to query
		EntityView projectView =  createProjectView();
		// CSV download from the view
		DownloadFromTableRequest request = new DownloadFromTableRequest();
		request.setSql("select * from "+projectView.getId());
		request.setWriteHeader(true);
		request.setIncludeRowIdAndRowVersion(true);
		request.setIncludeEntityEtag(true);
		List<String[]> results = downloadCSV(adminUserInfo, request);
		assertEquals(4, results.size());
		String[] headers = results.get(0);
		String headerString = Arrays.toString(headers);
		String[] expected = new String[]{ROW_ID, ROW_VERSION, ROW_ETAG, ObjectField.name.name()};
		String expectedString = Arrays.toString(expected);
		assertEquals(expectedString, headerString);
	}

	private void checkResults(List<String[]> results, List<String[]> input, boolean includeRowAndVersion) throws IOException,
			FileNotFoundException {
		assertNotNull(results);
		assertEquals(input.size(), results.size());
		for (int i = 0; i < input.size(); i++) {
			assertArrayEquals(input.get(i), Arrays.copyOfRange(results.get(i), includeRowAndVersion ? 2 : 0, results.get(i).length));
		}
	}
	
	/**
	 * Create a project view with three rows.
	 * @return
	 * @throws Exception 
	 */
	private EntityView createProjectView() throws Exception{
		String uuid = UUID.randomUUID().toString();
		List<String> projectIds = new LinkedList<String>();
		// Create three projects
		for(int i=0; i<3; i++){
			Project project = new Project();
			project.setName(uuid+"-"+i);
			String projectId = entityManager.createEntity(adminUserInfo, project, null);
			projectIds.add(projectId);
		}
		toDelete.addAll(projectIds);
		
		// Create a projectView
		ColumnModel nameColumn = columnModelMapper.getColumnModels(ViewObjectType.ENTITY, ObjectField.name).get(0);

		schema = Lists.newArrayList(nameColumn);
		headers = TableModelUtils.getIds(schema);
		EntityView view = new EntityView();
		view.setName(uuid+"-view");
		view.setScopeIds(projectIds);
		view.setColumnIds(Lists.newArrayList(nameColumn.getId()));
		view.setType(ViewType.project);
		tableId = entityManager.createEntity(adminUserInfo, view, null);
		toDelete.add(tableId);
		ViewScope scope = new ViewScope();
		scope.setViewEntityType(ViewEntityType.entityview);
		scope.setScope(projectIds);
		scope.setViewType(ViewType.project);
		tableViewManager.setViewSchemaAndScope(adminUserInfo, headers, scope, tableId);
		// Wait for the three rows to appear in the view
		int expectedRowCount = 3;
		waitForConsistentQuery(adminUserInfo, "SELECT * FROM "+tableId, expectedRowCount);
		return entityManager.getEntity(adminUserInfo, tableId, EntityView.class);
	}

	private List<String[]> createTable() throws NotFoundException, IOException {
		// Create one column of each type
		List<ColumnModel> temp = new LinkedList<ColumnModel>();
		temp.add(TableModelTestUtils.createColumn(0L, "a", ColumnType.STRING));
		temp.add(TableModelTestUtils.createColumn(0L, "b", ColumnType.INTEGER));
		temp.add(TableModelTestUtils.createColumn(0L, "c", ColumnType.DOUBLE));
		schema = new LinkedList<ColumnModel>();
		for (ColumnModel cm : temp) {
			cm = columnManager.createColumnModel(adminUserInfo, cm);
			schema.add(cm);
		}
		List<String> headers = TableModelUtils.getIds(schema);		
		tableId = asyncHelper.createTable(adminUserInfo, UUID.randomUUID().toString(), null, headers, false).getId();
		// Create some CSV data
		List<String[]> input = new ArrayList<String[]>(3);
		input.add(new String[] { "a", "b", "c" });
		input.add(new String[] { "AAA", "2", "1.1" });
		input.add(new String[] { null, "3", "1.2" });
		input.add(new String[] { "FFF", "4", null });
		input.add(new String[] { "ZZZ", null, "1.3" });
		// This is the starting input stream
		CSVReader reader = TableModelTestUtils.createReader(input);
		// Write the CSV to the table
		CSVToRowIterator iterator = new CSVToRowIterator(schema, reader, true, null);
		transactionManager.executeInTransaction(adminUserInfo, tableId, txContext -> {			
			try {
				return tableEntityManager.appendRowsAsStream(adminUserInfo, tableId, schema, iterator, null, null, txContext);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
		return input;
	}
	
	/**
	 * Download a CSV for the given request.
	 * @param request
	 * @return
	 * @throws Throwable 
	 * @throws InterruptedException 
	 * @throws NotFoundException 
	 */
	List<String[]> downloadCSV(UserInfo user, DownloadFromTableRequest request) throws Throwable {
		// submit the job
		AsynchronousJobStatus status = asynchJobStatusManager.startJob(user, request);
		// Wait for the job to complete.
		status = waitForStatus(status);
		assertNotNull(status);
		assertNotNull(status.getResponseBody());
		assertTrue(status.getResponseBody() instanceof DownloadFromTableResult);
		DownloadFromTableResult response = (DownloadFromTableResult) status.getResponseBody();
		assertNotNull(response.getEtag());
		assertNotNull(response.getResultsFileHandleId());
		// Get the filehandle
		fileHandle = (S3FileHandle) fileHandleDao.get(response.getResultsFileHandleId());
		// Read the CSV
		CSVReader csvReader;
		assertEquals("text/csv", fileHandle.getContentType());
		assertNotNull(fileHandle.getContentMd5());
		if (request.getFileName() == null) {
			assertNotNull(fileHandle.getFileName());
		} else {
			assertEquals(request.getFileName(), fileHandle.getFileName());
		}
		// Download the file
		File temp = File.createTempFile("DownloadCSV", "."+CSVUtils.guessExtension(request.getCsvTableDescriptor() == null ? null : request.getCsvTableDescriptor()
				.getSeparator()));
		try{
			s3Client.getObject(new GetObjectRequest(fileHandle.getBucketName(), fileHandle.getKey()), temp);
			// Load the CSV data
			csvReader = new CSVReader(new FileReader(temp));
			try {
				return csvReader.readAll();
			} finally {
				csvReader.close();
			}
		}finally{
			temp.delete();
		}
	}

	private RowSet waitForConsistentQuery(UserInfo user, String sql) throws Exception {
		Integer expectedRowCount = null;
		return waitForConsistentQuery(user, sql,expectedRowCount);
	}
	
	/**
	 * Wait for the query results for a given query.
	 * @param user
	 * @param sql
	 * @param expectedRowCount If not null, then will continue to wait while the row count is less than the expected count.
	 * @return
	 * @throws Exception
	 */
	private RowSet waitForConsistentQuery(UserInfo user, String sql, Integer expectedRowCount) throws Exception {
		long start = System.currentTimeMillis();
		QueryOptions options = new QueryOptions().withRunQuery(true).withRunCount(false).withReturnFacets(false);
		Query query = new Query();
		query.setSql(sql);
		while(true){
			assertTrue("Timed out waiting for table index worker to make the table available.", (System.currentTimeMillis()-start) <  MAX_WAIT_MS);
			try {
				RowSet results = tableQueryManger.querySinglePage(mockProgressCallback, adminUserInfo, query, options).getQueryResult().getQueryResults();
				if(expectedRowCount != null) {
					if(results.getRows() == null || results.getRows().size() < expectedRowCount) {
						System.out.println("Waiting for row count: "+expectedRowCount);
						Thread.sleep(1000);
						continue;
					}
				}
				return results;
			}  catch (LockUnavilableException e) {
				System.out.println("Waiting for table lock: "+e.getLocalizedMessage());
			} catch (TableUnavailableException e) {
				System.out.println("Waiting for table index worker to build table. Status: "+e.getStatus());
			}
			Thread.sleep(1000);
		}
	}
	
	private AsynchronousJobStatus waitForStatus(AsynchronousJobStatus status) throws Throwable{
		long start = System.currentTimeMillis();
		while(!AsynchJobState.COMPLETE.equals(status.getJobState())) {
			AsynchJobUtils.throwExceptionIfFailed(status);
			System.out.println("Waiting for job to complete: Message: "+status.getProgressMessage()+" progress: "+status.getProgressCurrent()+"/"+status.getProgressTotal());
			assertTrue("Timed out waiting for table status",(System.currentTimeMillis()-start) < MAX_WAIT_MS);
			Thread.sleep(1000);
			// Get the status again 
			status = this.asynchJobStatusManager.getJobStatus(adminUserInfo, status.getJobId());
		}
		return status;
	}
	
}
