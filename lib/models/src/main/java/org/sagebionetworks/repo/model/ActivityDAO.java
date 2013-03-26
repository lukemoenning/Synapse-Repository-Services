package org.sagebionetworks.repo.model;

import org.sagebionetworks.repo.model.message.ChangeType;
import org.sagebionetworks.repo.model.provenance.Activity;
import org.sagebionetworks.repo.web.NotFoundException;

public interface ActivityDAO extends MigratableDAO {

	/**
	 * Create an Activity
	 * @param dto
	 * @return
	 * @throws DatastoreException
	 * @throws InvalidModelException
	 */
	public String create(Activity dto) throws DatastoreException, InvalidModelException;

	/**
	 * Create an Activity from Back (for migration only)
	 * @param dto
	 * @return
	 * @throws DatastoreException
	 * @throws InvalidModelException
	 */
	public String createFromBackup(Activity dto) throws DatastoreException, InvalidModelException;


	/**
	 * Updates the object.
	 *
	 * @param dto
	 * @throws DatastoreException
	 */
	public Activity update(Activity activity) throws InvalidModelException,
			NotFoundException, ConflictingUpdateException, DatastoreException;

	/**
	 * Updates the 'shallow' properties of an object from backup.
	 * @param <T>
	 * @param activity
	 * @return
	 * @throws InvalidModelException
	 * @throws NotFoundException
	 * @throws ConflictingUpdateException
	 * @throws DatastoreException
	 */
	public Activity updateFromBackup(Activity activity) throws InvalidModelException,
	NotFoundException, ConflictingUpdateException, DatastoreException;
	
	/**
	 * Retrieves the object given its id
	 * 
	 * @param id
	 * @return
	 * @throws DatastoreException
	 * @throws NotFoundException
	 */
	public Activity get(String id) throws DatastoreException, NotFoundException;
		
	/**
	 * delete the object given by the given ID
	 * 
	 * @param id
	 *            the id of the object to be deleted
	 * @throws DatastoreException
	 * @throws NotFoundException
	 */
	public void delete(String id) throws DatastoreException;

	/**
	 * Locks the activity and increments its eTag
	 * Note: You cannot call this method outside of a transaction.
	 * @param id
	 * @param eTag
	 * @return
	 * @throws NotFoundException
	 * @throws ConflictingUpdateException
	 * @throws DatastoreException
	 */
	public String lockActivityAndGenerateEtag(String id, String eTag, ChangeType changeType) throws NotFoundException, ConflictingUpdateException, DatastoreException; 

	/**
	 * Lock activity row for deletion and send delete message
	 * Note: You cannot call this method outside of a transaction.
	 * @param id
	 * @param changeType
	 */
	public void sendDeleteMessage(String id);
	
	/**
	 * @param id
	 * @return Returns true if the activity id exists in the database
	 */
	public boolean doesActivityExist(String id);
		
	/**
	 * @param activityId activity id
	 * @param limit 0 based limit
	 * @param offset 0 based offset
	 * @return Returns a PaginatedResults of references that were generated by the given activity id 
	 */
	public PaginatedResults<Reference> getEntitiesGeneratedBy(String activityId, int limit, int offset);

}
