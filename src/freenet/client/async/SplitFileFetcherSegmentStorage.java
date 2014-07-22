package freenet.client.async;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;

import freenet.crypt.ChecksumOutputStream;
import freenet.keys.CHKBlock;
import freenet.keys.CHKDecodeException;
import freenet.keys.CHKEncodeException;
import freenet.keys.CHKVerifyException;
import freenet.keys.ClientCHK;
import freenet.keys.ClientCHKBlock;
import freenet.keys.NodeCHK;
import freenet.support.Logger;
import freenet.support.MemoryLimitedChunk;
import freenet.support.MemoryLimitedJob;
import freenet.support.io.LockableRandomAccessThing.RAFLock;
import freenet.support.io.NativeThread;

/** Represents a single segment, in memory and on disk. Handles storage and decoding. Note that the
 * on-disk data, and therefore the read-in metadata, may be inaccurate; we check everything 
 * opportunistically. Hence we are very robust (but not completely immune) to disk corruption.
 * @see SplitFileFetcherStorage */
public class SplitFileFetcherSegmentStorage {

    /** The segment number within the splitfile */
    final int segNo;
    /** Offset to the segment's block data. Initially we fill this up. */
    final long segmentBlockDataOffset;
    /** Offset to the segment's status metadata storage. */
    final long segmentStatusOffset;
    /** Length of the segment status */
    final int segmentStatusLength;
    /** Length of the segment status for purposes of locating it on disk, may be larger than
     * segmentStatusLength. */
    final int segmentStatusPaddedLength;
    /** Offset to the segment's key list */
    final long segmentKeyListOffset;
    /** Length of the segment key list */
    final int segmentKeyListLength;
    /** The splitfile */
    final SplitFileFetcherStorage parent;
    /** Count of data blocks (actual data divided up into CHKs, though the last one will be 
     * padded). Numbered 0 .. dataBlocks-1. */
    public final int dataBlocks;
    /** Count of cross-segment check blocks. These occur only in larger splitfiles and count as
     * data blocks for segment-level FEC, but they also count as check blocks for cross-segment
     * level FEC. Generally between 0 and 3. Numbered dataBlocks .. dataBlocks+crossSegmentCheckBlocks-1 */
    public final int crossSegmentCheckBlocks;
    /** Count of check blocks (generated by FEC). Numbered 
     * dataBlocks+crossSegmentCheckBlocks .. dataBlocks+crossSegmentCheckBlocks+checkBlocks-1 */
    public final int checkBlocks;
    /** How many times have blocks been retried? Null if maxRetries = -1 */
    private final int[] retries;
    /** Which blocks have we tried to fetch? And presumably failed, if we don't have them. Updated
     * when a block fails and written lazily. */
    private final boolean[] tried;
    /** Which blocks have we already found? May be inaccurate, checked on FEC decode. */
    private final boolean[] blocksFound;
    /** What is the order of the blocks on disk? Should be kept consistent with blocksFound! Is 
     * read from disk on startup and may be inaccurate, checked on FEC decode. Elements: -1 = not
     * fetched yet. */
    private final short[] blocksFetched;
    private int blocksFetchedCount;
    /** True if we have downloaded and decoded all the data blocks and cross-segment check blocks,
     * and written them to their final location in the parent storage file. */
    private boolean succeeded;
    /** True if we have not only downloaded and decoded, but also finished with encoding and 
     * queueing healing blocks. */
    private boolean finished;
    /** True if the segment has been cancelled, has failed, ran out of retries or otherwise is no
     * longer running but hasn't succeeded. */
    private boolean failed;
    /** True if the metadata needs writing but isn't going to be written immediately. */
    private boolean metadataDirty;
    /** The cross segments for each data or cross-segment check block. This allows us to tell the
     * cross-segments when we may have data to decode. The array is null if there are no 
     * cross-segments, and the elements are null if there is no associated cross-segment. */
    private final SplitFileFetcherCrossSegmentStorage[] crossSegmentsByBlock;
    private SoftReference<SplitFileSegmentKeys> keysCache;
    private boolean tryDecode;
    private int crossDataBlocksAllocated;
    private int crossCheckBlocksAllocated;
    
    private boolean logMINOR;
    static {
        Logger.registerClass(SplitFileFetcherSegmentStorage.class);
    }
    
    public SplitFileFetcherSegmentStorage(SplitFileFetcherStorage parent, int segNumber, 
            short splitfileType, int dataBlocks, int checkBlocks, int crossCheckBlocks,
            long segmentDataOffset, long segmentKeysOffset, long segmentStatusOffset, 
            boolean trackRetries, SplitFileSegmentKeys keys) {
        this.parent = parent;
        this.segNo = segNumber;
        this.dataBlocks = dataBlocks;
        this.checkBlocks = checkBlocks;
        this.crossSegmentCheckBlocks = crossCheckBlocks;
        int total = dataBlocks + checkBlocks + crossSegmentCheckBlocks;
        if(trackRetries)
            retries = new int[total];
        else
            retries = null;
        tried = new boolean[total];
        blocksFound = new boolean[total];
        int minFetched = dataBlocks + crossSegmentCheckBlocks;
        if(crossCheckBlocks != 0)
            crossSegmentsByBlock = new SplitFileFetcherCrossSegmentStorage[minFetched];
        else
            crossSegmentsByBlock = null;
        blocksFetched = new short[minFetched];
        for(int i=0;i<blocksFetched.length;i++) blocksFetched[i] = -1;
        segmentStatusLength = storedSegmentStatusLength(dataBlocks, checkBlocks, crossCheckBlocks, 
                trackRetries);
        segmentStatusPaddedLength = paddedStoredSegmentStatusLength(dataBlocks, checkBlocks, 
                crossCheckBlocks, trackRetries);
        segmentKeyListLength = 
            storedKeysLength(dataBlocks, checkBlocks, trackRetries);
        this.segmentBlockDataOffset = segmentDataOffset;
        this.segmentKeyListOffset = segmentKeysOffset;
        this.segmentStatusOffset = segmentStatusOffset;
        // This must be passed in here or we will read the uninitialised keys!
        keysCache = new SoftReference<SplitFileSegmentKeys>(keys);
    }

    public SplitFileSegmentKeys getSegmentKeys() throws IOException {
        synchronized(this) {
            if(keysCache != null) {
                SplitFileSegmentKeys cached = keysCache.get();
                if(cached != null) return cached;
            }
            SplitFileSegmentKeys keys = readSegmentKeys();
            if(keys == null) return keys;
            keysCache = new SoftReference<SplitFileSegmentKeys>(keys);
            return keys;
        }
    }

    private SplitFileSegmentKeys readSegmentKeys() throws IOException {
        RAFLock lock = parent.raf.lockOpen();
        try {
            SplitFileSegmentKeys keys = new SplitFileSegmentKeys(dataBlocks + crossSegmentCheckBlocks, checkBlocks, parent.splitfileSingleCryptoKey, parent.splitfileSingleCryptoAlgorithm);
            byte[] buf = new byte[SplitFileSegmentKeys.storedKeysLength(dataBlocks, checkBlocks, parent.splitfileSingleCryptoKey != null)];
            parent.raf.pread(segmentKeyListOffset, buf, 0, buf.length);
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(buf));
            keys.readKeys(dis, false);
            keys.readKeys(dis, true);
            return keys;
        } finally {
            lock.unlock();
        }
    }
    
    /** Write the status metadata to disk, after a series of updates. */
    public boolean writeMetadata() throws IOException {
        return writeMetadata(true);
    }

    /** Write the status metadata to disk, after a series of updates. */
    public boolean writeMetadata(boolean force) throws IOException {
        RAFLock lock = parent.raf.lockOpen();
        try {
            return innerWriteMetadata(force);
        } finally {
            lock.unlock();
        }
    }
    
    /** Read all the blocks, encode them according to their supposed keys and check that they are
     * in fact the blocks that they should be. If the metadata is inaccurate, update it and 
     * writeMetadata(). If we have enough blocks to decode, and we don't have all the blocks, then 
     * schedule a decode on the FEC thread. 
     * @return True if we scheduled a decode or are already finished. False if we do not have 
     * enough blocks to decode and need to fetch more blocks. */
    public boolean tryStartDecode() {
        synchronized(this) {
            if(succeeded || failed) return false;
            if(blocksFetchedCount < blocksForDecode()) return false;
            if(tryDecode) return true;
            tryDecode = true;
        }
        long limit = totalBlocks() * CHKBlock.DATA_LENGTH + 
            Math.max(parent.fecCodec.maxMemoryOverheadDecode(dataBlocks + crossSegmentCheckBlocks, checkBlocks),
                    parent.fecCodec.maxMemoryOverheadEncode(dataBlocks + crossSegmentCheckBlocks, checkBlocks));
        parent.memoryLimitedJobRunner.queueJob(new MemoryLimitedJob(limit) {
            
            @Override
            public int getPriority() {
                return NativeThread.LOW_PRIORITY;
            }
            
            @Override
            public boolean start(MemoryLimitedChunk chunk) {
                try {
                    innerTryStartDecode(chunk);
                } catch (IOException e) {
                    Logger.error(this, "Failed to decode "+this+" because of disk error: "+e, e);
                    parent.failOnDiskError(e);
                } finally {
                    chunk.release();
                    synchronized(this) {
                        tryDecode = false;
                    }
                }
                return true;
            }
            
        });
        return true;
    }
    
    /** Attempt FEC decoding */
    private void innerTryStartDecode(MemoryLimitedChunk chunk) throws IOException {
        synchronized(this) {
            if(succeeded || failed) return;
        }
        int totalBlocks = totalBlocks();
        byte[][] allBlocks = readAllBlocks();
        SplitFileSegmentKeys keys = getSegmentKeys();
        if(allBlocks == null || keys == null) {
            return;
        }
        class MyBlock {
            final byte[] buf;
            final short blockNumber;
            final short slot;
            MyBlock(byte[] buf, short blockNumber, short slot) {
                this.buf = buf;
                this.blockNumber = blockNumber;
                this.slot = slot;
            }
        }
        ArrayList<MyBlock> maybeBlocks = new ArrayList<MyBlock>();
        int fetchedCount = 0;
        boolean changedSomething = false;
        synchronized(this) {
            boolean[] used = new boolean[totalBlocks];
            for(short i=0;i<blocksFetched.length;i++) {
                if(blocksFetched[i] < 0 || blocksFetched[i] > totalBlocks) {
                    Logger.warning(this, "Inconsistency decoding splitfile: slot "+i+" has bogus block number "+blocksFetched[i]);
                    if(blocksFetched[i] != -1)
                        blocksFetched[i] = -1;
                    maybeBlocks.add(new MyBlock(allBlocks[i], (short)-1, i));
                    changedSomething = true;
                    continue;
                } else if(used[blocksFetched[i]]) {
                    Logger.warning(this, "Inconsistency decoding splitfile: slot "+i+" has duplicate block number "+blocksFetched[i]);
                    blocksFetched[i] = -1;
                    changedSomething = true;
                    continue;
                } else {
                    if(logMINOR) Logger.minor(this, "Found block "+blocksFetched[i]+" in slot "+i);
                    maybeBlocks.add(new MyBlock(allBlocks[i], blocksFetched[i], i));
                    used[i] = true;
                    fetchedCount++;
                }
            }
            if(fetchedCount < blocksForDecode()) {
                int count = 0;
                for(int i=0;i<totalBlocks;i++) {
                    if(!used[i]) {
                        changedSomething = true;
                        blocksFound[i] = false;
                    }
                    if(blocksFound[i]) count++;
                }
                if(count != blocksFetchedCount) {
                    Logger.warning(this, "Corrected block count to "+count+" from "+blocksFetchedCount);
                    blocksFetchedCount = count;
                    changedSomething = true;
                }
            }
        }
        if(fetchedCount < blocksForDecode()) {
            if(changedSomething)
                writeMetadata();
            return;
        }
        
        // Check the blocks and put them into the correct positions.
        int validBlocks = 0;
        int validDataBlocks = 0;
        byte[][] dataBlocks = new byte[this.dataBlocks + this.crossSegmentCheckBlocks][];
        byte[][] checkBlocks = new byte[this.checkBlocks][];
        
        for(MyBlock test : maybeBlocks) {
            boolean failed = false;
            short blockNumber = test.blockNumber;
            byte[] buf = test.buf;
            ClientCHK decodeKey = blockNumber == -1 ? null : keys.getKey(blockNumber, null, false);
            // Encode it to check whether the key is the same.
            try {
                ClientCHKBlock block =
                    ClientCHKBlock.encodeSplitfileBlock(buf, decodeKey.getCryptoKey(), decodeKey.getCryptoAlgorithm());
                ClientCHK actualKey = block.getClientKey();
                if(decodeKey == null || !decodeKey.equals(actualKey)) {
                    // Is it a different block?
                    blockNumber = (short)keys.getBlockNumber(actualKey, null);
                    if(blockNumber == -1) {
                        Logger.error(this, "Block which should be block #"+blockNumber+" for segment "+this+" is not valid for key "+decodeKey);
                        failed = true;
                    } else {
                        synchronized(this) {
                            blocksFetched[test.slot] = blockNumber;
                            changedSomething = true;
                        }
                    }
                }
                
            } catch (CHKEncodeException e) {
                Logger.error(this, "Block which should be "+blockNumber+" for segment "+this+" cannot be encoded for key "+decodeKey);
                failed = true;
            }
            if(!failed) {
                validBlocks++;
                if(blockNumber < blocksForDecode())
                    validDataBlocks++;
                if(blockNumber < dataBlocks.length)
                    dataBlocks[blockNumber] = buf;
                else
                    checkBlocks[blockNumber - dataBlocks.length] = buf;
            } else {
                synchronized(this) {
                    if(blocksFetched[test.slot] == test.blockNumber) {
                        blocksFetched[test.slot] = (short)-1;
                        blocksFetchedCount--;
                        changedSomething = true;
                    }
                }
            }
        }
        allBlocks = null;
        maybeBlocks.clear();
        maybeBlocks = null;
        if(validBlocks < blocksForDecode()) {
            if(changedSomething)
                writeMetadata();
            return;
        }
        boolean[] dataBlocksPresent = new boolean[dataBlocks.length];
        boolean[] checkBlocksPresent = new boolean[checkBlocks.length];
        for(int i=0;i<dataBlocks.length;i++) {
            if(dataBlocks[i] == null) {
                dataBlocks[i] = new byte[CHKBlock.DATA_LENGTH];
            } else {
                dataBlocksPresent[i] = true;
            }
        }
        for(int i=0;i<checkBlocks.length;i++) {
            if(checkBlocks[i] == null) {
                checkBlocks[i] = new byte[CHKBlock.DATA_LENGTH];
            } else {
                checkBlocksPresent[i] = true;
            }
        }
        if(validDataBlocks < blocksForDecode()) {
            if(logMINOR) Logger.minor(this, "Decoding in memory for "+this);
            parent.fecCodec.decode(dataBlocks, checkBlocks, dataBlocksPresent, checkBlocksPresent, CHKBlock.DATA_LENGTH);
        }
        writeAllDataBlocks(dataBlocks);
        // Report success at this point.
        parent.finishedSuccess(this);
        triggerAllCrossSegmentCallbacks();
        parent.fecCodec.encode(dataBlocks, checkBlocks, checkBlocksPresent, CHKBlock.DATA_LENGTH);
        queueHeal(dataBlocks, checkBlocks, dataBlocksPresent, checkBlocksPresent);
        dataBlocks = null;
        checkBlocks = null;
        writeMetadata();
        // Now we've REALLY finished.
        synchronized(this) {
            finished = true;
        }
        parent.finishedEncoding(this);
    }

    private void queueHeal(byte[][] dataBlocks, byte[][] checkBlocks, boolean[] dataBlocksPresent, boolean[] checkBlocksPresent) throws IOException {
        for(int i=0;i<dataBlocks.length;i++) {
            if(dataBlocksPresent[i]) continue;
            if(!tried[i]) continue;
            queueHeal(i, dataBlocks[i]);
        }
        for(int i=0;i<checkBlocks.length;i++) {
            if(checkBlocksPresent[i]) continue;
            if(!tried[i+dataBlocks.length]) continue;
            queueHeal(i+dataBlocks.length, checkBlocks[i]);
        }
    }

    private void queueHeal(int blockNumber, byte[] data) throws IOException {
        byte[] cryptoKey;
        byte cryptoAlgorithm;
        if(parent.splitfileSingleCryptoKey != null) {
            cryptoKey = parent.splitfileSingleCryptoKey;
            cryptoAlgorithm = parent.splitfileSingleCryptoAlgorithm;
        } else {
            ClientCHK key = getSegmentKeys().getKey(blockNumber, null, false);
            cryptoKey = key.getCryptoKey();
            cryptoAlgorithm = key.getCryptoAlgorithm();
        }
        parent.fetcher.queueHeal(data, cryptoKey, cryptoAlgorithm);
    }

    private byte[][] readAllBlocks() throws IOException {
        // FIXME consider using a single big byte[].
        byte[][] ret = new byte[blocksForDecode()][];
        for(int i=0;i<ret.length;i++)
            ret[i] = readBlock(i);
        return ret;
    }

    private void triggerAllCrossSegmentCallbacks() {
        SplitFileFetcherCrossSegmentStorage[] crossSegmentsByBlockCopy;
        synchronized(this) {
            if(crossSegmentsByBlock == null) return;
            crossSegmentsByBlockCopy = Arrays.copyOf(this.crossSegmentsByBlock, this.crossSegmentsByBlock.length);
            for(int i=0;i<crossSegmentsByBlock.length;i++)
                crossSegmentsByBlock[i] = null;
        }
        for(SplitFileFetcherCrossSegmentStorage s : crossSegmentsByBlockCopy) {
            if(s != null)
                s.onFetchedRelevantBlock(this);
        }
    }

    /** Write a full set of data blocks to disk and update the metadata accordingly. */
    private void writeAllDataBlocks(byte[][] dataBlocks) throws IOException {
        RAFLock lock = parent.raf.lockOpen();
        try {
            synchronized(this) {
                for(int i=0;i<blocksForDecode();i++) {
                    writeDownloadedBlock(i, dataBlocks[i]);
                    blocksFound[i] = true;
                    blocksFetched[i] = (short)i;
                }
                blocksFetchedCount = blocksForDecode();
                succeeded = true;
            }
        } finally {
            lock.unlock();
        }
    }

    private final int totalBlocks() {
        return dataBlocks + crossSegmentCheckBlocks + checkBlocks;
    }

    /** A block has been fetched which the caller believes is one of ours. Check whether it is in 
     * fact ours, and that we don't have it already. Find the key and decode it, and add it to our
     * collection. If any cross-segments are waiting for this block, tell them. If we can decode,
     * do so. Can be quite involved, should be called off-thread.
     * @param key
     * @param block
     * @throws IOException If we were unable to write the block to disk.
     * @return
     */
    public boolean onGotKey(NodeCHK key, CHKBlock block) throws IOException {
        SplitFileSegmentKeys keys = getSegmentKeys();
        if(keys == null) return false;
        short blockNumber;
        ClientCHK decodeKey;
        synchronized(this) {
            if(succeeded || failed) return false;
            blockNumber = (short)keys.getBlockNumber(key, blocksFound);
            if(blockNumber == -1) return false;
            if(blocksFound[blockNumber]) 
                return false; // Even if this is inaccurate, it will be corrected on a FEC attempt.
            if(blocksFetchedCount >= blocksForDecode())
                return false;
            decodeKey = keys.getKey(blockNumber, null, false);
        }
        ClientCHKBlock decodedBlock;
        byte[] decodedData;
        try {
            decodedBlock = new ClientCHKBlock(block, decodeKey);
            decodedData = decodedBlock.memoryDecode();
        } catch (CHKVerifyException e) {
            Logger.error(this, "Verify failed on block for "+decodeKey);
            return false;
        } catch (CHKDecodeException e) {
            Logger.error(this, "Decode failed on block for "+decodeKey);
            return false;
        }
        SplitFileFetcherCrossSegmentStorage callback = null;
        // LOCKING We have to do the write inside the lock to prevent parallel decodes messing up etc.
        synchronized(this) {
            if(succeeded || failed) return false;
            if(blocksFound[blockNumber]) return false;
            if(blocksFetchedCount >= blocksForDecode())
                return false;
            int slotNumber = findFreeSlot();
            assert(slotNumber != -1);
            blocksFetched[slotNumber] = blockNumber;
            blocksFound[blockNumber] = true;
            RAFLock lock = parent.raf.lockOpen();
            try {
                writeDownloadedBlock(slotNumber, decodedData);
                innerWriteMetadata(true);
            } catch (IOException e) {
                blocksFetched[slotNumber] = -1;
                blocksFound[blockNumber] = false;
                Logger.error(this, "Unable to write downloaded block to disk: "+e, e);
                throw e;
            } finally {
                lock.unlock();
            }
            blocksFetchedCount++;
            if(crossSegmentsByBlock != null && blockNumber < crossSegmentsByBlock.length) {
                callback = crossSegmentsByBlock[blockNumber];
                crossSegmentsByBlock[blockNumber] = null;
            }
        }
        if(callback != null)
            callback.onFetchedRelevantBlock(this);
        // Write metadata immediately. Finding a block is a big deal. The OS may cache it anyway.
        writeMetadata();
        tryStartDecode();
        return true;
    }

    private synchronized int findFreeSlot() {
        for(int i=0;i<blocksFetched.length;i++) {
            if(blocksFetched[i] == -1) return i;
        }
        return -1;
    }

    /** Caller must have already lock()'ed parent.raf and synchronized(this). 
     * @throws IOException */
    private void writeDownloadedBlock(int slotNumber, byte[] data) throws IOException {
        // FIXME Do we need to pad here for really old splitfiles, or does the FEC code do it?
        if(data.length != CHKBlock.DATA_LENGTH) throw new IllegalArgumentException();
        if(slotNumber >= blocksForDecode()) throw new IllegalArgumentException();
        long offset = blockOffset(slotNumber);
        parent.raf.pwrite(offset, data, 0, data.length);
    }

    private long blockOffset(int slotNumber) {
        return segmentBlockDataOffset + slotNumber * CHKBlock.DATA_LENGTH;
    }

    /** Write the metadata (status). Caller should already have taken parent.raf.lock() and 
     * synchronized(this). Metadata is fairly sparse on disk, we are expected to deduce it (and
     * check it) when constructing.
     * @throws IOException */
    private boolean innerWriteMetadata(boolean force) throws IOException {
        ByteArrayOutputStream baos;
        synchronized(this) {
            if(!(force || metadataDirty)) return false;
            baos = new ByteArrayOutputStream();
            try {
                DataOutputStream dos = new DataOutputStream(baos);
                for(short s : blocksFetched)
                    dos.writeShort(s);
                if(retries != null) {
                    for(int r : retries)
                        dos.writeInt(r);
                }
                for(boolean b : tried)
                    dos.writeBoolean(b);
                dos.close();
            } catch (IOException e) {
                throw new Error(e); // Impossible!
            }
            metadataDirty = false;
        }
        byte[] buf = baos.toByteArray();
        assert(buf.length == segmentStatusLength);
        parent.raf.pwrite(segmentStatusOffset, buf, 0, buf.length);
        return true;
    }
    
    public static int storedSegmentStatusLength(int dataBlocks, int checkBlocks, int crossCheckBlocks, 
            boolean trackRetries) {
        int fetchedBlocks = dataBlocks + crossCheckBlocks;
        int totalBlocks = dataBlocks + checkBlocks + crossCheckBlocks;
        return fetchedBlocks * 2 + (trackRetries ? (totalBlocks * 4) : 0) + totalBlocks;
    }
    
    public static int paddedStoredSegmentStatusLength(int dataBlocks, int checkBlocks, int crossCheckBlocks, 
            boolean trackRetries) {
        return storedSegmentStatusLength(dataBlocks, checkBlocks, crossCheckBlocks, trackRetries);
    }
    
    private final int blocksForDecode() {
        return dataBlocks + crossSegmentCheckBlocks;
    }

    public synchronized boolean isFinished() {
        return finished || failed;
    }
    
    public synchronized boolean hasSucceeded() {
        return succeeded;
    }

    /** Write content to an OutputStream. We already have raf.lock(). 
     * @throws IOException */
    void writeToInner(OutputStream os) throws IOException {
        // FIXME if we use readAllBlocks() we'll need to run on the memory limited queue???
        for(int i=0;i<dataBlocks;i++) {
            byte[] buf = readBlock(i);
            os.write(buf);
        }
    }

    /** Read a single block from a specific slot, which could be any block number. 
     * @throws IOException If an error occurred reading the data from disk. */
    private byte[] readBlock(int slotNumber) throws IOException {
        if(slotNumber >= blocksForDecode()) throw new IllegalArgumentException();
        long offset = blockOffset(slotNumber);
        byte[] buf = new byte[CHKBlock.DATA_LENGTH];
        parent.raf.pread(offset, buf, 0, buf.length);
        return buf;
    }
    
    public void onNonFatalFailure(int blockNumber) {
        boolean changed = false;
        synchronized(this) {
            if(retries != null) {
                retries[blockNumber]++;
                changed = true;
            }
            if(!tried[blockNumber]) {
                tried[blockNumber] = true;
                changed = true;
            }
        }
        if(changed)
            lazyWriteMetadata();
    }

    /** The metadata has been updated. We should write it ... at some point. */
    private void lazyWriteMetadata() {
        parent.lazyWriteMetadata();
    }
    
    /** Allocate a cross-segment data block. Note that this algorithm must be reproduced exactly 
     * for splitfile compatibility; the Random seed is actually determined by the splitfile metadata.
     * @param seg The cross-segment to allocate a block for.
     * @param random PRNG seeded from the splitfile metadata, which determines which blocks to 
     * allocate in a deterministic manner.
     * @return The data block number allocated.
     */
    public int allocateCrossDataBlock(SplitFileFetcherCrossSegmentStorage seg, Random random) {
        int size = dataBlocks;
        if(crossDataBlocksAllocated == size) return -1;
        int x = 0;
        for(int i=0;i<10;i++) {
            x = random.nextInt(size);
            if(crossSegmentsByBlock[x] == null) {
                crossSegmentsByBlock[x] = seg;
                crossDataBlocksAllocated++;
                return x;
            }
        }
        for(int i=0;i<size;i++) {
            x++;
            if(x == size) x = 0;
            if(crossSegmentsByBlock[x] == null) {
                crossSegmentsByBlock[x] = seg;
                crossDataBlocksAllocated++;
                return x;
            }
        }
        throw new IllegalStateException("Unable to allocate cross data block even though have not used all slots up???");
    }

    /** Allocate a cross-segment check block. Note that this algorithm must be reproduced exactly 
     * for splitfile compatibility; the Random seed is actually determined by the splitfile metadata.
     * @param seg The cross-segment to allocate a block for.
     * @param random PRNG seeded from the splitfile metadata, which determines which blocks to 
     * allocate in a deterministic manner.
     * @return The block number allocated (between dataBlocks and dataBlocks+crossSegmentCheckBlocks).
     */
    public int allocateCrossCheckBlock(SplitFileFetcherCrossSegmentStorage seg, Random random) {
        if(crossCheckBlocksAllocated == crossSegmentCheckBlocks) return -1;
        int x = dataBlocks + crossSegmentCheckBlocks - random.nextInt(crossSegmentCheckBlocks);
        for(int i=0;i<crossSegmentCheckBlocks;i++) {
            x++;
            if(x == dataBlocks + crossSegmentCheckBlocks) x = dataBlocks;
            if(crossSegmentsByBlock[x] == null) {
                crossSegmentsByBlock[x] = seg;
                crossCheckBlocksAllocated++;
                return x;
            }
        }
        throw new IllegalStateException("Unable to allocate cross check block even though have not used all slots up???");
    }

    static int storedKeysLength(int dataBlocks, int checkBlocks, boolean commonDecryptKey) {
        return SplitFileSegmentKeys.storedKeysLength(dataBlocks, checkBlocks, commonDecryptKey) + 4;
    }
    
    void writeKeysWithChecksum() throws IOException {
        SplitFileSegmentKeys keys = getSegmentKeys();
        assert(this.dataBlocks + this.crossSegmentCheckBlocks == keys.dataBlocks);
        assert(this.checkBlocks == keys.checkBlocks);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ChecksumOutputStream cos = new ChecksumOutputStream(baos, new CRC32());
        DataOutputStream dos = new DataOutputStream(cos);
        try {
            keys.writeKeys(dos, false);
            keys.writeKeys(dos, true);
            // Write the checksum, only including the keys.
            dos.writeInt((int)cos.getValue());
        } catch (IOException e) {
            // Impossible!
            throw new Error(e);
        }
        byte[] buf = baos.toByteArray();
        RAFLock lock = parent.raf.lockOpen();
        try {
            parent.raf.pwrite(segmentKeyListOffset, buf, 0, buf.length);
        } finally {
            lock.unlock();
        }
    }

    public boolean definitelyWantKey(NodeCHK key) {
        synchronized(this) {
            if(succeeded || failed) return false;
        }
        SplitFileSegmentKeys keys;
        try {
            keys = getSegmentKeys();
        } catch (IOException e) {
            parent.failOnDiskError(e);
            return false;
        }
        synchronized(this) {
            return keys.getBlockNumber(key, blocksFound) >= 0;
        }
    }

    /** Write minimal fixed metadata for the segment. This should include lengths rather than 
     * offsets. Does not write cross-segment block assignments; these are handled by the 
     * cross-segments. 
     * @throws IOException */
    public void writeFixedMetadata(DataOutputStream dos) throws IOException {
        dos.writeShort(VERSION);
        dos.writeInt(this.dataBlocks);
        dos.writeInt(this.crossSegmentCheckBlocks);
        dos.writeInt(this.checkBlocks);
        dos.writeInt(segmentStatusPaddedLength);
        dos.writeInt(segmentKeyListLength);
    }

    static final short VERSION = 1;

}
