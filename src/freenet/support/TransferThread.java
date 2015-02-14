/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.Collection;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientGetCallback;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientPutCallback;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.node.PrioRunnable;
import freenet.support.io.TempBucketFactory;

/**
 * A thread which periodically wakes up and iterates to start fetches and/or inserts.
 * 
 * When calling <code>start()</code>, the thread will iterate the first time after <code>getStartupDelay()</code> milliseconds.
 * After each iteration, it will sleep for <code>getSleepTime()</code> milliseconds.
 * 
 * @deprecated
 *     This class uses {@link TrivialTicker} in a bogus way.
 *     See <a href="https://bugs.freenetproject.org/view.php?id=6423">issue 6423</a>.<br>
 *     The Web Of Trust plugin contains a bugfixed version, please use that one instead.<br>
 *     (The fixed version was not moved to fred because TransferThread is only used by WOT and
 *     Freetalk, not by fred itself. Thus it should probably be moved to a common library.)
 * @author xor
 */
@Deprecated
public abstract class TransferThread implements PrioRunnable, ClientGetCallback, ClientPutCallback {
	
	private final String mName;
	protected final Node mNode;
	protected final HighLevelSimpleClient mClient;
	protected final ClientContext mClientContext;
	protected final TempBucketFactory mTBF;
	
	private TrivialTicker mTicker;
	
	private final Collection<ClientGetter> mFetches = createFetchStorage();
	private final Collection<BaseClientPutter> mInserts = createInsertStorage();
	
	public TransferThread(Node myNode, HighLevelSimpleClient myClient, String myName) {
		mNode = myNode;
		mClient = myClient;
		mClientContext = mNode.clientCore.clientContext;
		mTBF = mNode.clientCore.tempBucketFactory;
		mName = myName;
		
		mTicker = new TrivialTicker(mNode.executor);
	}
	
	/**
	 * Tells this TransferThread to start it's execution. You have to call this after constructing an object of an implementing class - it must not
	 * be called in the constructors of implementing classes.
	 */
	public void start() {
		Logger.debug(this, "Starting...");
		mTicker.queueTimedJob(this, mName, getStartupDelay(), false, true);
	}
	
	/** Specify the priority of this thread. Priorities to return can be found in class NativeThread. */
	@Override
	public abstract int getPriority();

	@Override
	public void run() {
		long sleepTime = SECONDS.toMillis(1);
		try {
			Logger.debug(this, "Loop running...");
			iterate();
			sleepTime = getSleepTime();
		}
		catch(Exception e) {
			Logger.error(this, "Error in iterate() or getSleepTime() probably", e);
		}
		finally {
			Logger.debug(this, "Loop finished. Sleeping for " + MINUTES.convert(sleepTime, MILLISECONDS) + " minutes.");
			mTicker.queueTimedJob(this, mName, sleepTime, false, true);
		}
	}
	
	/**
	 * Wakes up the thread so that iterate() is called.
	 */
	public void nextIteration() {
		mTicker.rescheduleTimedJob(this, mName, 0);
	}
	
	protected void abortAllTransfers() {
		Logger.debug(this, "Trying to stop all fetches & inserts...");
		
		abortFetches();
		abortInserts();
	}
	
	protected void abortFetches() {
		Logger.debug(this, "Trying to stop all fetches...");
		if(mFetches != null) synchronized(mFetches) {
			ClientGetter[] fetches = mFetches.toArray(new ClientGetter[mFetches.size()]);
			int fcounter = 0;
			for(ClientGetter fetch : fetches) {
				/* This calls onFailure which removes the fetch from mFetches on the same thread, therefore we need to copy to an array */
				fetch.cancel(mNode.clientCore.clientContext);
				++fcounter;
			}
			
			Logger.debug(this, "Stopped " + fcounter + " current fetches.");
		}
	}
	
	protected void abortInserts() {
		Logger.debug(this, "Trying to stop all inserts...");
		if(mInserts != null) synchronized(mInserts) {
			BaseClientPutter[] inserts = mInserts.toArray(new BaseClientPutter[mInserts.size()]);
			int icounter = 0;
			for(BaseClientPutter insert : inserts) {
				/* This calls onFailure which removes the fetch from mFetches on the same thread, therefore we need to copy to an array */
				insert.cancel(mNode.clientCore.clientContext);
				++icounter;
			}
			Logger.debug(this, "Stopped " + icounter + " current inserts.");
		}
	}
	
	protected void addFetch(ClientGetter g) {
		synchronized(mFetches) {
			mFetches.add(g);
		}
	}
	
	protected void removeFetch(ClientGetter g) {
		synchronized(mFetches) {
			mFetches.remove(g);
		}
		Logger.debug(this, "Removed request for " + g.getURI());
	}
	
	protected void addInsert(BaseClientPutter p) {
		synchronized(mInserts) {
			mInserts.add(p);
		}
	}
	
	protected void removeInsert(BaseClientPutter p) {
		synchronized(mInserts) {
			mInserts.remove(p);
		}
		Logger.debug(this, "Removed insert for " + p.getURI());
	}
	
	protected int fetchCount() {
		synchronized(mFetches) {
			return mFetches.size();
		}
	}
	
	protected int insertCount() {
		synchronized(mInserts) {
			return mInserts.size();
		}
	}
	
	public void terminate() {
		Logger.debug(this, "Terminating...");
		mTicker.shutdown();
		try {
			abortAllTransfers();
		}
		catch(RuntimeException e) {
			Logger.error(this, "Aborting all transfers failed", e);
		}
		Logger.debug(this, "Terminated.");
	}
	
	
	protected abstract Collection<ClientGetter> createFetchStorage();
	
	protected abstract Collection<BaseClientPutter> createInsertStorage();
	
	protected abstract long getStartupDelay();

	protected abstract long getSleepTime();
	
	/**
	 * Called by the TransferThread after getStartupDelay() milliseconds for the first time and then after each getSleepTime() milliseconds.
	 */
	protected abstract void iterate();

	
	/* Fetches */
	
	/**
	 * You have to do "finally { removeFetch() }" when using this function.
	 */
	@Override
	public abstract void onSuccess(FetchResult result, ClientGetter state);

	/**
	 * You have to do "finally { removeFetch() }" when using this function.
	 */
	@Override
	public abstract void onFailure(FetchException e, ClientGetter state);

	/* Inserts */
	
	/**
	 * You have to do "finally { removeInsert() }" when using this function.
	 */
	@Override
	public abstract void onSuccess(BaseClientPutter state);

	/**
	 * You have to do "finally { removeInsert() }" when using this function.
	 */
	@Override
	public abstract void onFailure(InsertException e, BaseClientPutter state);

	@Override
	public abstract void onFetchable(BaseClientPutter state);

	@Override
	public abstract void onGeneratedURI(FreenetURI uri, BaseClientPutter state);

}