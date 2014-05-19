package org.sagebionetworks.repo.web.controller;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang.BooleanUtils;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.ConflictingUpdateException;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.EntityId;
import org.sagebionetworks.repo.model.InvalidModelException;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.ServiceConstants;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.auth.NewIntegrationTestUser;
import org.sagebionetworks.repo.model.daemon.BackupRestoreStatus;
import org.sagebionetworks.repo.model.message.ChangeMessages;
import org.sagebionetworks.repo.model.message.FireMessagesResult;
import org.sagebionetworks.repo.model.message.PublishResults;
import org.sagebionetworks.repo.model.status.StackStatus;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.repo.web.UrlHelpers;
import org.sagebionetworks.repo.web.service.ServiceProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Since this controller is for internal use only we removed the ControllerInfo annotation.
 *
 */
@Controller
@RequestMapping(UrlHelpers.REPO_PATH)
public class AdministrationController extends BaseController {
	
	@Autowired
	private ServiceProvider serviceProvider;
	
	/**
	 * Get the status of a running daemon (either a backup or restore)
	 * @param daemonId
	 * @param userId
	 * @param header
	 * @param request
	 * @return
	 * @throws DatastoreException
	 * @throws InvalidModelException
	 * @throws UnauthorizedException
	 * @throws NotFoundException
	 * @throws IOException
	 * @throws ConflictingUpdateException
	 */
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = { 
			UrlHelpers.ENTITY_DAEMON_ID
			}, method = RequestMethod.GET)
	public @ResponseBody
	BackupRestoreStatus getStatus(
			@PathVariable String daemonId,
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestHeader HttpHeaders header,
			HttpServletRequest request)
			throws DatastoreException, InvalidModelException,
			UnauthorizedException, NotFoundException, IOException, ConflictingUpdateException {

		return serviceProvider.getAdministrationService().getStatus(daemonId, userId, header, request);
	}
	
	/**
	 * Terminate a running daemon.  This has no effect if the daemon is already terminated.
	 * @param daemonId
	 * @param userId
	 * @param header
	 * @param request
	 * @throws DatastoreException
	 * @throws InvalidModelException
	 * @throws UnauthorizedException
	 * @throws NotFoundException
	 * @throws IOException
	 * @throws ConflictingUpdateException
	 */
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequestMapping(value = { 
			UrlHelpers.ENTITY_DAEMON_ID
			}, method = RequestMethod.DELETE)
	public @ResponseBody
	void terminateDaemon(
			@PathVariable String daemonId,
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestHeader HttpHeaders header,
			HttpServletRequest request)
			throws DatastoreException, InvalidModelException,
			UnauthorizedException, NotFoundException, IOException, ConflictingUpdateException {

		serviceProvider.getAdministrationService().terminateDaemon(daemonId, userId, header, request);
	}
	
	
	/**
	 * Get the current status of the stack
	 * @param userId
	 * @param header
	 * @param request
	 * @return
	 * @throws DatastoreException
	 * @throws InvalidModelException
	 * @throws UnauthorizedException
	 * @throws NotFoundException
	 * @throws IOException
	 * @throws ConflictingUpdateException
	 */
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = { 
			UrlHelpers.STACK_STATUS
			}, method = RequestMethod.GET)
	public @ResponseBody
	StackStatus getStackStatus() {

		return serviceProvider.getAdministrationService().getStackStatus();
	}
	
	/**
	 * Update the current status of the stack.
	 * 
	 * @param userId
	 * @param header
	 * @param request
	 * @return
	 * @throws DatastoreException
	 * @throws InvalidModelException
	 * @throws UnauthorizedException
	 * @throws NotFoundException
	 * @throws IOException
	 * @throws ConflictingUpdateException
	 */
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = { 
			UrlHelpers.STACK_STATUS
			}, method = RequestMethod.PUT)
	public @ResponseBody
	StackStatus updateStatusStackStatus(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestHeader HttpHeaders header,
			HttpServletRequest request) throws DatastoreException, NotFoundException, UnauthorizedException, IOException {

		return serviceProvider.getAdministrationService().updateStatusStackStatus(userId, header, request);
	}
	
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = { UrlHelpers.CHANGE_MESSAGES }, method = RequestMethod.GET)
	public @ResponseBody
	ChangeMessages listChangeMessages(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestParam Long startChangeNumber, @RequestParam String type,
			@RequestParam Long limit) throws DatastoreException,
			NotFoundException {
		// The type can be null
		ObjectType typeEnum = null;
		if (type != null) {
			typeEnum = ObjectType.valueOf(type);
		}
		// Pass it along
		return serviceProvider.getAdministrationService().listChangeMessages(userId, startChangeNumber, typeEnum, limit);
	}
	
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = { UrlHelpers.REBROADCAST_MESSAGES }, method = RequestMethod.POST)
	public @ResponseBody
	PublishResults rebroadcastChangeMessagesToQueue(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestParam String queueName,
			@RequestParam Long startChangeNumber, @RequestParam String type,
			@RequestParam Long limit) throws DatastoreException,
			NotFoundException {
		// The type can be null
		ObjectType typeEnum = null;
		if (type != null) {
			typeEnum = ObjectType.valueOf(type);
		}
		// Pass it along
		return serviceProvider.getAdministrationService().rebroadcastChangeMessagesToQueue(userId, queueName, startChangeNumber, typeEnum, limit);
	}

	/**
	 * Refires all the change messages
	 **/
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = { UrlHelpers.REFIRE_MESSAGES }, method = RequestMethod.GET)
	public @ResponseBody
	FireMessagesResult refireChangeMessagesToQueue(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestParam Long startChangeNumber,
			@RequestParam Long limit) throws DatastoreException,
			NotFoundException {
		// Pass it along
		return serviceProvider.getAdministrationService().reFireChangeMessages(userId, startChangeNumber, limit);
	}
	
	/**
	 * Get current change message number
	 */
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = { UrlHelpers.CURRENT_NUMBER }, method = RequestMethod.GET)
	public @ResponseBody
	FireMessagesResult getCurrentChangeNumber(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId
			) throws DatastoreException,
			NotFoundException {
		// Pass it along
		return serviceProvider.getAdministrationService().getCurrentChangeNumber(userId);
	}
	
	
	/**
	 * Clears the Synapse DOI table.
	 */
	@RequestMapping(value = {UrlHelpers.ADMIN_DOI_CLEAR}, method = RequestMethod.DELETE)
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void
	clearDoi(@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId)
			throws NotFoundException, UnauthorizedException, DatastoreException {
		serviceProvider.getAdministrationService().clearDoi(userId);
	}

	/**
	 * Clears the specified dynamo table.
	 */
	@RequestMapping(value = {UrlHelpers.ADMIN_DYNAMO_CLEAR_TABLE}, method = RequestMethod.DELETE)
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void clearDynamoTable(
			@PathVariable String tableName,
	        @RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestParam(value = ServiceConstants.DYNAMO_HASH_KEY_NAME_PARAM, required = true) String hashKeyName,
			@RequestParam(value = ServiceConstants.DYNAMO_RANGE_KEY_NAME_PARAM, required = true) String rangeKeyName,
			HttpServletRequest request) throws DatastoreException, NotFoundException {
		serviceProvider.getAdministrationService().clearDynamoTable(userId, tableName, hashKeyName, rangeKeyName);
	}

	/**
	 * Creates a user with specific state to be used for integration testing
	 */
	@RequestMapping(value = {UrlHelpers.ADMIN_USER}, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	public @ResponseBody EntityId createIntegrationTestUser(
			@RequestBody NewIntegrationTestUser userSpecs,
	        @RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId) 
	        		throws NotFoundException {
		return serviceProvider.getAdministrationService().createTestUser(userId, userSpecs);
	}

	/**
	 * Deletes a user.  All FKs must be deleted before this will succeed
	 */
	@RequestMapping(value = {UrlHelpers.ADMIN_USER_ID}, method = RequestMethod.DELETE)
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteUser(
			@PathVariable String id, 
	        @RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId) 
	        		throws NotFoundException {
		serviceProvider.getAdministrationService().deleteUser(userId, id);
	}

	/**
	 * Wait for a while or release all waiters
	 */
	@RequestMapping(value = { UrlHelpers.ADMIN_WAIT }, method = RequestMethod.GET)
	@ResponseStatus(HttpStatus.OK)
	public void waitForTesting(@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestParam(required = false) Boolean release) throws Exception {
		serviceProvider.getAdministrationService().waitForTesting(userId, BooleanUtils.isTrue(release));
	}
}
