package org.sagebionetworks.repo.model.message;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.ObservableEntity;
import org.sagebionetworks.repo.model.dbo.dao.DBOChangeDAO;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.util.ThreadLocalProvider;
import org.sagebionetworks.util.ValidateArgument;
import org.sagebionetworks.util.collections.Transform;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;

import java.time.Instant;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;


/**
 * Basic implementation of TransactionalMessenger.  Messages are bound to the current transaction and thread.
 * The messages will be sent when the current transaction commits.  If the transaction rolls back the messages will not be sent.
 * 
 * This class utilizes TransactionSynchronizationManager for all transaction management.
 * 
 * @author John
 *
 */
@Service
public class TransactionalMessengerImpl implements TransactionalMessenger {
	
	static private Logger log = LogManager.getLogger(TransactionalMessengerImpl.class);
	
	private static final String TRANSACTIONAL_MESSANGER_IMPL_MESSAGES = "TransactionalMessangerImpl.Messages";

	private static final ThreadLocal<Long> currentUserIdThreadLocal = ThreadLocalProvider.getInstance(AuthorizationConstants.USER_ID_PARAM, Long.class);
	
	private DBOChangeDAO changeDAO;
	
	private TransactionSynchronizationProxy transactionSynchronizationManager;
	
	
	/**
	 * For IoC
	 * @param txManager
	 * @param changeDAO
	 * @param transactionSynchronizationManager
	 */
	@Autowired
	public TransactionalMessengerImpl(DBOChangeDAO changeDAO, TransactionSynchronizationProxy transactionSynchronizationManager) {
		this.changeDAO = changeDAO;
		this.transactionSynchronizationManager = transactionSynchronizationManager;
	}

	/**
	 * The list of observers that are notified of messages after a commit.
	 */
	private List<TransactionalMessengerObserver> observers = new LinkedList<TransactionalMessengerObserver>();
	
	@WriteTransaction
	@Override
	public void sendDeleteMessageAfterCommit(String objectId, ObjectType objectType) {
		sendMessageAfterCommit(objectId, objectType, ChangeType.DELETE);
	}
	
	@WriteTransaction
	@Override
	public void sendMessageAfterCommit(String objectId, ObjectType objectType, ChangeType changeType) {
		ChangeMessage message = new ChangeMessage();
		message.setChangeType(changeType);
		message.setObjectType(objectType);
		message.setObjectId(objectId);
		sendMessageAfterCommit(message);
	}
	
	
	@WriteTransaction
	@Override
	public void sendMessageAfterCommit(ObservableEntity entity, ChangeType changeType) {
		ChangeMessage message = new ChangeMessage();
		message.setChangeType(changeType);
		message.setObjectType(entity.getObjectType());
		message.setObjectId(entity.getIdString());
		sendMessageAfterCommit(message);
	}
	
	@WriteTransaction
	@Override
	public void sendMessageAfterCommit(ChangeMessage message) {
		if(message == null) throw new IllegalArgumentException("Message cannot be null");
		if(message.getUserId() == null){
			// If the userId was not provided attempt to the current user from the thread local.
			message.setUserId(currentUserIdThreadLocal.get());
		}
		appendToBoundMessages(message);
	}
	
	@WriteTransaction
	@Override
	public void sendMessageAfterCommit(MessageToSend toSend) {
		sendMessageAfterCommit(toSend.buildChangeMessage());
	}
	
	@Override
	public void publishMessageAfterCommit(LocalStackMessage message) {
		ValidateArgument.required(message, "The message");
		ValidateArgument.required(message.getObjectId(), "The message.objectId");
		ValidateArgument.required(message.getObjectType(), "The message.objectType");

		if (message.getTimestamp() == null) {
			message.setTimestamp(Date.from(Instant.now()));
		}
		
		if (message instanceof LocalStackChangeMesssage) {
			LocalStackChangeMesssage changeMessage = (LocalStackChangeMesssage) message;
			ValidateArgument.required(changeMessage.getChangeType(), "The message.changeType");
			ValidateArgument.required(changeMessage.getUserId(), "The message.userId");
			
			if (changeMessage.getChangeNumber() == null) {
				changeMessage.setChangeNumber(-1L);
			}
		}

		if (transactionSynchronizationManager.isSynchronizationActive()) {

			transactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					for (TransactionalMessengerObserver observer : observers) {
						observer.fireLocalStackMessage(message);
					}
				}
			});
		} else {
			for (TransactionalMessengerObserver observer : observers) {
				observer.fireLocalStackMessage(message);
			}
		}
	}

	private <T extends Message> void appendToBoundMessages(T message) {
		// Make sure we are in a transaction.
		assertActiveSynchronization();
		// Bind this message to the transaction
		// Get the bound list of messages if it already exists.
		Map<MessageKey, Message> currentMessages = getCurrentBoundMessages();
		// If we already have a message going out for this object then we needs replace it with the latest.
		currentMessages.put(new MessageKey(message), message);
		// Register a handler if needed
		registerHandlerIfNeeded();
	}
	
	private void assertActiveSynchronization() {
		if (transactionSynchronizationManager.isSynchronizationActive()) {
			return;
		}
		throw new IllegalStateException("Cannot send a transactional message because there is no transaction");
	}

	/**
	 * Get the change messages that are currently bound to this transaction.
	 * 
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private Map<MessageKey, Message> getCurrentBoundMessages() {
		String queueKey = TRANSACTIONAL_MESSANGER_IMPL_MESSAGES;
		Map<MessageKey, Message> currentMessages = (Map<MessageKey, Message>) transactionSynchronizationManager
				.getResource(queueKey);
		if(currentMessages == null){
			// This is the first time it is called for this thread.
			currentMessages = Maps.newHashMap();
			// Bind this list to the transaction.
			transactionSynchronizationManager.bindResource(queueKey, currentMessages);
		}
		return currentMessages;
	}
	
	/**
	 * For each thread we need to add a handler, but we only need to do this if a handler does not already exist.
	 * 
	 */
	private void registerHandlerIfNeeded(){
		// Inspect the current handlers.
		List<TransactionSynchronization> currentList = transactionSynchronizationManager.getSynchronizations();
		for (TransactionSynchronization sync : currentList) {
			if (sync instanceof SynchronizationHandler) {
				return;
			}
		}
		transactionSynchronizationManager.registerSynchronization(new SynchronizationHandler());
	}
	
	/**
	 * Handles the Synchronization Handler
	 * @author John
	 *
	 */
	private class SynchronizationHandler implements TransactionSynchronization {
		@Override
		public void afterCompletion(int status) {
			// Unbind any messages from this transaction.
			// Note: We unbind even if the status was a roll back (status=1) as we will not send
			// messages when a roll back occurs.
			if(log.isTraceEnabled()){
				log.trace("Unbinding resources");
			}
			// Unbind any messages from this transaction.
			transactionSynchronizationManager.unbindResourceIfPossible(TRANSACTIONAL_MESSANGER_IMPL_MESSAGES);
		}

		@Override
		public void afterCommit() {
			// Log the messages
			Map<MessageKey, Message> currentMessages = getCurrentBoundMessages();
			// For each observer fire the message.
			for(TransactionalMessengerObserver observer: observers){
				// Fire each message.
				for (Message message : currentMessages.values()) {
					if (message instanceof ChangeMessage) {
						observer.fireChangeMessage((ChangeMessage) message);
					}  else {
						throw new IllegalArgumentException("Unknown message type " + message.getClass().getName());
					}
					if (log.isTraceEnabled()) {
						log.trace("Firing a change event: " + message + " for observer: " + observer);
					}
				}
			}
			// Clear the lists
			currentMessages.clear();
		}

		@Override
		public void beforeCommit(boolean readOnly) {
			// write the changes to the database
			Map<MessageKey, Message> currentMessages = getCurrentBoundMessages();

			// filter out modification message, since this only applies to the non-modifcation only messages
			List<ChangeMessage> list = Transform.toList(Iterables.filter(currentMessages.values(), new Predicate<Message>() {
				@Override
				public boolean apply(Message input) {
					return input instanceof ChangeMessage;
				}
			}), new Function<Message, ChangeMessage>() {
				@Override
				public ChangeMessage apply(Message input) {
					return (ChangeMessage) input;
				}
			});

			// Create the list of DBOS
			List<ChangeMessage> updatedList;
			updatedList = changeDAO.replaceChange(list);
			// Now replace each entry on the map with the update message
			for (ChangeMessage message : updatedList) {
				currentMessages.put(new MessageKey(message), message);
			}
		}
		
	}
	
	/**
	 * Register an observer that will be notified when there is a message after a commit.
	 * 
	 * @param observer
	 */
	public void registerObserver(TransactionalMessengerObserver observer){
		// Add this to the list of observers.
		observers.add(observer);
	}
	
	/**
	 * Remove an observer.
	 * @param observer
	 * @return true if observer was registered.
	 */
	public boolean removeObserver(TransactionalMessengerObserver observer){
		// remove from the list
		return observers.remove(observer);
	}

	@Override
	public List<TransactionalMessengerObserver> getAllObservers() {
		// Return a copy of the list.
		return new LinkedList<TransactionalMessengerObserver>(observers);
	}
	
	@WriteTransaction
	@Override
	public void registerMessagesSent(ObjectType type, List<ChangeMessage> page) {
		this.changeDAO.registerMessageSent(type, page);
	}

	@Override
	public List<ChangeMessage> listUnsentMessages(long limit) {
		return this.changeDAO.listUnsentMessages(limit);
	}
}
