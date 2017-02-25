package org.sagebionetworks.repo.manager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.Node;
import org.sagebionetworks.repo.model.NodeDAO;
import org.sagebionetworks.repo.model.NodeInheritanceDAO;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.repo.web.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;

import com.google.common.collect.Lists;

/**
 * 
 * @author jmhill
 *
 */
public class NodeInheritanceManagerImpl implements NodeInheritanceManager {

	@Autowired
	NodeInheritanceDAO nodeInheritanceDao;
	@Autowired
	NodeDAO nodeDao;

	@WriteTransaction
	@Override
	public void nodeParentChanged(String nodeId, String parentNodeId)
			throws NotFoundException, DatastoreException {
		nodeParentChanged(nodeId, parentNodeId, true);
	}

	@WriteTransaction
	@Override
	public void nodeParentChanged(String nodeId, String parentNodeId, boolean skipBenefactor) 
			throws NotFoundException, DatastoreException {

		if (nodeId == null) {
			throw new IllegalArgumentException("nodeId cannot be null.");
		}
		if (parentNodeId == null) {
			throw new IllegalArgumentException("parentNodeId cannot be null.");
		}
		// Must lock before looking up the benefactor of each.
		nodeDao.lockNodes(Lists.newArrayList(nodeId,parentNodeId));
		// First determine who this node is inheriting from
		String oldBenefactorId = nodeInheritanceDao.getBenefactorCached(nodeId);
		if (skipBenefactor && oldBenefactorId.equals(nodeId)) {
			return;
		}

		// Here node needs to be set to nearest benefactor and children
		// need to be adjusted accordingly. Nearest benefactor will be 
		// set to what the parent node has as benefactor
		String changeToId = nodeInheritanceDao.getBenefactorCached(parentNodeId);
		// Lock both the old and new benefactor before making a change See PLFM-3713
		nodeDao.lockNodes(Lists.newArrayList(oldBenefactorId,changeToId));
		if (skipBenefactor) {
			changeAllChildrenTo(oldBenefactorId, nodeId, changeToId);
		} else {
			changeAllChildrenTo(null, nodeId, changeToId);
		}
	}

	@WriteTransaction
	@Override
	public void setNodeToInheritFromItself(String nodeId) throws NotFoundException, DatastoreException {
		setNodeToInheritFromItself(nodeId, true);
	}

	@WriteTransaction
	@Override
	public void setNodeToInheritFromItself(String nodeId, boolean skipBenefactor)
			throws NotFoundException, DatastoreException {

		if (nodeId == null) {
			throw new IllegalArgumentException("nodeId cannot be null.");
		}

		if (skipBenefactor) {
			// Find all children of this node that are currently inheriting from the same 
			// benefactor.
			String currentBenefactorId = nodeInheritanceDao.getBenefactorCached(nodeId);
			// There is nothing to do if a node already inherits from itself
			if(nodeId.equals(currentBenefactorId)) return;
			// Change all the required children
			changeAllChildrenTo(currentBenefactorId, nodeId, nodeId);
		} else {
			changeAllChildrenTo(null, nodeId, nodeId);
		}
	}

	@WriteTransaction
	@Override
	public void setNodeToInheritFromNearestParent(String nodeId) throws NotFoundException, DatastoreException {
		// First determine who this node is inheriting from.
		String currentBenefactorId = nodeInheritanceDao.getBenefactorCached(nodeId);
		Node node = nodeDao.getNode(nodeId);
		String changeToId = null;
		if(node.getParentId() == null){
			// this node should inherit from itself.
			// If this node is already inheriting from itself then there is nothing to do
			if(currentBenefactorId == nodeId) return;
			// Change to this node
			changeToId = nodeId;
		}else{
			// Change to the parent's benefactor
			changeToId = nodeInheritanceDao.getBenefactorCached(node.getParentId());
		}
		// Do the change
		// Change all the required children
		changeAllChildrenTo(currentBenefactorId, nodeId, changeToId);
	}
	
	/**
	 * Change all children of the given parent(parentId), that are currently inheriting from the given id (currentlyInheritingFromId),
	 * to now inherit from given id (changeToInheritFromId).
	 * @param currentlyInheritingFromId - Children currently inheriting from this nodeId will be changed.
	 * @param parentId - The parent 
	 * @param changeToInheritFromId
	 * @throws NotFoundException
	 * @throws DatastoreException 
	 */
	private void changeAllChildrenTo(String currentlyInheritingFromId, String parentId, String changeToInheritFromId) throws NotFoundException, DatastoreException{
		// This is the set of nodes that will need to change.
		Set<String> toChange = new HashSet<String>();
		// Recursively build up the set to change
		// Now add all children
		addChildrenToChange(currentlyInheritingFromId, parentId, toChange);
		// We sort the Ids to prevent deadlock on concurrent updates
		String[] sortedArrayIds = toChange.toArray(new String[toChange.size()]);
		Arrays.sort(sortedArrayIds);
		// Now update each node.
		for(String idToChange: sortedArrayIds){
			nodeInheritanceDao.addBeneficiary(idToChange, changeToInheritFromId);
		}
	}
	
	/**
	 * This is a recursive method that finds all of the nodes that need to change.
	 * @param currentBenefactorId
	 * @param parentId
	 * @param toChange
	 * @throws NotFoundException
	 * @throws DatastoreException 
	 */
	private void addChildrenToChange(String currentBenefactorId, String parentId, Set<String> toChange) throws NotFoundException, DatastoreException{
		// Find find the parent's benefactor
		String parentCurrentBenefactorId = nodeInheritanceDao.getBenefactorCached(parentId);
		if (currentBenefactorId == null || parentCurrentBenefactorId.equals(currentBenefactorId)){
			toChange.add(parentId);
			// Now check this node's children
			Iterator<String> it = nodeDao.getChildrenIds(parentId).iterator();
			// Add each child
			while(it.hasNext()){
				String childId = it.next();
				addChildrenToChange(currentBenefactorId, childId, toChange);
			}
		}
	}

	/**
	 * Get the benefactor of a node.
	 * @throws DatastoreException 
	 */
	@Override
	public String getBenefactorCached(String nodeId) throws NotFoundException, DatastoreException {
		return nodeInheritanceDao.getBenefactorCached(nodeId);
	}
	
	/**
	 * Add a beneficiary to a node
	 * @throws NotFoundException 
	 * @throws DatastoreException 
	 */
	@WriteTransaction
	@Override
	public void addBeneficiary(String beneficiaryId, String toBenefactorId) throws NotFoundException, DatastoreException {
		nodeInheritanceDao.addBeneficiary(beneficiaryId, toBenefactorId);
	}

}
