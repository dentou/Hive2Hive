package org.hive2hive.core.network.data.futures;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import net.tomp2p.dht.FuturePut;
import net.tomp2p.dht.FutureRemove;
import net.tomp2p.dht.StorageLayer.PutStatus;
import net.tomp2p.futures.BaseFutureAdapter;
import net.tomp2p.peers.Number640;
import net.tomp2p.peers.PeerAddress;

import org.hive2hive.core.H2HConstants;
import org.hive2hive.core.model.NetworkContent;
import org.hive2hive.core.network.data.DataManager;
import org.hive2hive.core.network.data.parameters.IParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A put future adapter for verifying a put of a {@link NetworkContent} object. Provides failure handling and
 * a blocking wait.</br></br>
 * 
 * <b>Failure Handling</b></br>
 * Putting can fail when the future object failed, when the future object contains wrong data or the
 * responding node detected a failure. See {@link PutStatus} for possible failures. If putting fails the
 * adapter retries it to a certain threshold (see {@link H2HConstants.PUT_RETRIES}). All puts are
 * asynchronous. That's why the future listener attaches himself to the new future objects so that the adapter
 * can finally notify his/her listener about a success or failure.
 * 
 * @author Seppi, Nico
 */
public class FuturePutListener extends BaseFutureAdapter<FuturePut> {

	private static final Logger logger = LoggerFactory.getLogger(FuturePutListener.class);

	private final IParameters parameters;
	private final DataManager dataManager;
	private final CountDownLatch latch;

	private boolean success = false;

	// used to count put retries
	private int putTries = 0;

	public FuturePutListener(IParameters parameters, DataManager dataManager) {
		this.parameters = parameters;
		this.dataManager = dataManager;
		this.latch = new CountDownLatch(1);
	}

	/**
	 * Wait (blocking) until the put is done
	 * 
	 * @return true if successful, false if not successful
	 */
	public boolean await() {
		try {
			latch.await();
		} catch (InterruptedException e) {
			logger.error("Could not wait until put has finished.", e);
		}

		return success;
	}

	@Override
	public void operationComplete(FuturePut future) throws Exception {
		if (future.isFailed()) {
			logger.warn("Put future was not successful. '{}'", parameters.toString());
			retryPut();
			return;
		} else if (future.rawResult().isEmpty()) {
			logger.warn("Returned raw results are empty. '{}'", parameters.toString());
			retryPut();
			return;
		}

		// analyze returned put status
		final List<PeerAddress> fail = new ArrayList<PeerAddress>();
		for (PeerAddress peeradress : future.rawResult().keySet()) {
			Map<Number640, Byte> map = future.rawResult().get(peeradress);
			if (map == null) {
				logger.warn("A node gave no status (null) back. '{}'", parameters.toString());
				fail.add(peeradress);
			} else {
				for (Number640 key : future.rawResult().get(peeradress).keySet()) {
					byte status = future.rawResult().get(peeradress).get(key);
					switch (PutStatus.values()[status]) {
						case OK:
							break;
						case FAILED:
						case FAILED_SECURITY:
							logger.warn("A node denied putting data. reason = '{}'. '{}'", PutStatus.values()[status],
									parameters.toString());
							fail.add(peeradress);
							break;
						default:
							logger.warn("Got an unknown status: {}", PutStatus.values()[status]);
					}
				}
			}
		}

		if ((double) fail.size() < ((double) future.rawResult().size()) / 2.0) {
			// majority of the contacted nodes responded with ok
			notifySuccess();
		} else {
			logger.warn("{} of {} contacted nodes failed.", fail.size(), future.rawResult().size());
			retryPut();
		}
	}

	/**
	 * Retries a put till a certain threshold is reached (see {@link H2HConstants.PUT_RETRIES}). Removes first
	 * the possibly succeeded puts. A {@link RetryPutListener} tries to put again the given content.
	 */
	private void retryPut() {
		if (putTries++ < H2HConstants.PUT_RETRIES) {
			logger.warn("Put retry #{}. '{}'", putTries, parameters.toString());
			// remove succeeded puts
			FutureRemove futureRemove = dataManager.removeVersionUnblocked(parameters);
			futureRemove.addListener(new BaseFutureAdapter<FutureRemove>() {
				@Override
				public void operationComplete(FutureRemove future) {
					if (future.isFailed()) {
						logger.warn("Put retry: Could not delete the newly put content. '{}'", parameters.toString());
					}
					// retry put, attach itself as listener
					dataManager.putUnblocked(parameters).addListener(FuturePutListener.this);
				}
			});
		} else {
			logger.error("Could not put data after {} tries. '{}'", putTries, parameters.toString());
			notifyFailure();
		}
	}

	private void notifySuccess() {
		// everything is ok
		success = true;
		latch.countDown();
	}

	/**
	 * Remove first potentially successful puts. Then notify the listener about the fail.
	 */
	private void notifyFailure() {
		// remove succeeded puts
		FutureRemove futureRemove = dataManager.removeVersionUnblocked(parameters);
		futureRemove.addListener(new BaseFutureAdapter<FutureRemove>() {
			@Override
			public void operationComplete(FutureRemove future) {
				if (future.isFailed()) {
					logger.warn("Put retry: Could not delete the newly put content. '{}'", parameters.toString());
				}

				success = false;
				latch.countDown();
			}
		});
	}
}
