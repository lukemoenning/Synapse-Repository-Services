package org.sagebionetworks.tool.migration.v3;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import org.mockito.Mockito;
import org.sagebionetworks.client.exceptions.SynapseException;

import org.sagebionetworks.repo.model.migration.MigrationType;
import org.sagebionetworks.repo.model.migration.RowMetadata;
import org.sagebionetworks.repo.model.status.StackStatus;
import org.sagebionetworks.repo.model.status.StatusEnum;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;

/**
 * Migration client test.
 * 
 * @author jmhill
 *
 */
public class MigrationClientTest {
	
	StubSynapseAdministration destSynapse;
	StubSynapseAdministration sourceSynapse;
	SynapseClientFactory mockFactory;
	MigrationClient migrationClient;
	
	@Before
	public void before() throws SynapseException{
		// Create the two stubs
		destSynapse = new StubSynapseAdministration("destination");
		sourceSynapse = new StubSynapseAdministration("source");
		mockFactory = Mockito.mock(SynapseClientFactory.class);
		when(mockFactory.createNewDestinationClient()).thenReturn(destSynapse);
		when(mockFactory.createNewSourceClient()).thenReturn(sourceSynapse);
		migrationClient = new MigrationClient(mockFactory);
	}
	
	@Test
	public void testSetDestinationStatus() throws SynapseException, JSONObjectAdapterException{
		// Set the status to down
		migrationClient.setDestinationStatus(StatusEnum.READ_ONLY, "Test message");
		// Only the destination should be changed
		StackStatus status = destSynapse.getCurrentStackStatus();
		StackStatus expected = new StackStatus();
		expected.setCurrentMessage("Test message");
		expected.setStatus(StatusEnum.READ_ONLY);
		assertEquals(expected, status);
		// The source should remain unmodified
		status = sourceSynapse.getCurrentStackStatus();
		expected = new StackStatus();
		expected.setCurrentMessage("Synapse is read for read/write");
		expected.setStatus(StatusEnum.READ_WRITE);
		assertEquals(expected, status);
	}
	
	/**
	 * Test the full migration of data from the source to destination.
	 * @throws Exception 
	 * 
	 */
	@Test
	public void testMigrateAllTypes() throws Exception{
		// Setup the destination
		LinkedHashMap<MigrationType, List<RowMetadata>> metadata = new LinkedHashMap<MigrationType, List<RowMetadata>>();
		// The first element should get deleted and second should get updated.
		List<RowMetadata> list = createList(new String[]{"0","1"}, new String[]{"e0","e1"});
		metadata.put(MigrationType.values()[0], list);
		// Setup a second type with no valuse
		list = createList(new String[]{}, new String[]{});
		metadata.put(MigrationType.values()[1], list);
		destSynapse.setMetadata(metadata);
		
		// setup the source
		metadata = new LinkedHashMap<MigrationType, List<RowMetadata>>();
		// The first element should get trigger an update and the second should trigger an add
		list = createList(new String[]{"1","2"}, new String[]{"e1changed","e2"});
		metadata.put(MigrationType.values()[0], list);
		// both values should get added
		list = createList(new String[]{"4","5"}, new String[]{"e4","e5"});
		metadata.put(MigrationType.values()[1], list);
		sourceSynapse.setMetadata(metadata);
		
		// Migrate the data
		migrationClient.migrateAllTypes(1l, 1000*60);
		// Now validate the results
		List<RowMetadata> expected0 = createList(new String[]{"1","2"}, new String[]{"e1changed","e2"});
		List<RowMetadata> expected1 = createList(new String[]{"4","5"}, new String[]{"e4","e5"});
		// check the state of the destination.
		assertEquals(expected0, destSynapse.getMetadata().get(MigrationType.values()[0]));
		assertEquals(expected1, destSynapse.getMetadata().get(MigrationType.values()[1]));
		// Check the state of the source
		assertEquals(expected0, sourceSynapse.getMetadata().get(MigrationType.values()[0]));
		assertEquals(expected1, sourceSynapse.getMetadata().get(MigrationType.values()[1]));
	}
	
	
	/**
	 * Helper to build up lists of metdata.
	 * @param ids
	 * @param etags
	 * @param mock
	 */
	public static List<RowMetadata> createList(String[] ids, String[] etags){
		List<RowMetadata> list = new LinkedList<RowMetadata>();
		for(int i=0;  i<ids.length; i++){
			if(ids[i] == null){
				list.add(null);
			}else{
				RowMetadata row = new RowMetadata();
				row.setId(ids[i]);
				row.setEtag(etags[i]);
				list.add(row);
			}
		}
		return list;
	}

}
