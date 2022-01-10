package org.sagebionetworks.table.worker;

import org.sagebionetworks.common.util.progress.ProgressCallback;
import org.sagebionetworks.repo.manager.table.MaterializedViewManager;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.table.TableState;
import org.sagebionetworks.repo.model.table.TableStatusChangeEvent;
import org.sagebionetworks.worker.TypedMessageDrivenRunner;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The worker listens for table status changes, if available will trigger a re-build of all the materialized views that depend on the tables
 */
public class MaterializedViewSourceUpdateWorker implements TypedMessageDrivenRunner<TableStatusChangeEvent> {
		
	private MaterializedViewManager manager;

	@Autowired
	public MaterializedViewSourceUpdateWorker(MaterializedViewManager manager) {
		this.manager = manager;
	}
	
	@Override
	public Class<TableStatusChangeEvent> getObjectClass() {
		return TableStatusChangeEvent.class;
	}

	@Override
	public void run(ProgressCallback progressCallback, TableStatusChangeEvent message) throws RecoverableMessageException, Exception {
		if (ObjectType.TABLE_STATUS_EVENT != message.getObjectType()) {
			throw new IllegalStateException("Unsupported object type: expected " + ObjectType.TABLE_STATUS_EVENT.name() + ", got " + message.getObjectType().name());
		}
		// We refresh the depending views only when the state changed to available
		if (message.getState() == TableState.AVAILABLE) {
			final IdAndVersion tableId = KeyFactory.idAndVersion(message.getObjectId(), message.getObjectVersion());
			
			manager.refreshDependentMaterializedViews(tableId);
		}
	}

}
