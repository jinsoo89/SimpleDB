package simpledb;

import java.io.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BufferPool manages the reading and writing of pages into memory from
 * disk. Access methods call into it to retrieve pages, and it fetches
 * pages from the appropriate location.
 * <p>
 * The BufferPool is also responsible for locking;  when a transaction fetches
 * a page, BufferPool checks that the transaction has the appropriate
 * locks to read/write the page.
 * 
 * @Threadsafe, all fields are final
 */
public class BufferPool {
    /** Bytes per page, including header. */
    private static final int DEFAULT_PAGE_SIZE = 4096;

    private static int pageSize = DEFAULT_PAGE_SIZE;
    
    /** Default number of pages passed to the constructor. This is used by
    other classes. BufferPool should use the numPages argument to the
    constructor instead. */
    public static final int DEFAULT_PAGES = 50;
    
    // fields
    private int numPages;
    private Map<PageId, Page> bufPool;
    private LockManager lockManager;

    /**
     * Creates a BufferPool that caches up to numPages pages.
     *
     * @param numPages maximum number of pages in this buffer pool.
     */
    public BufferPool(int numPages) {
        this.numPages = numPages;
        this.bufPool = new ConcurrentHashMap<>();
        this.lockManager = new LockManager();
    }
    
    public static int getPageSize() {
        return pageSize;
    }
    
    // THIS FUNCTION SHOULD ONLY BE USED FOR TESTING!!
    public static void setPageSize(int pageSize) {
    	    BufferPool.pageSize = pageSize;
    }
    
    // THIS FUNCTION SHOULD ONLY BE USED FOR TESTING!!
    public static void resetPageSize() {
    	    BufferPool.pageSize = DEFAULT_PAGE_SIZE;
    }

    /**
     * Retrieve the specified page with the associated permissions.
     * Will acquire a lock and may block if that lock is held by another
     * transaction.
     * <p>
     * The retrieved page should be looked up in the buffer pool.  If it
     * is present, it should be returned.  If it is not present, it should
     * be added to the buffer pool and returned.  If there is insufficient
     * space in the buffer pool, a page should be evicted and the new page
     * should be added in its place.
     *
     * @param tid the ID of the transaction requesting the page
     * @param pid the ID of the requested page
     * @param perm the requested permissions on the page
     */
    public Page getPage(TransactionId tid, PageId pid, Permissions perm)
        throws TransactionAbortedException, DbException {
        Page p;
        
        if (perm == Permissions.READ_ONLY) {
            // read only permission - acquire shared lock
            lockManager.acquireShared(tid, pid);
        } else {
            // read-write permission - acquire exclusive lock
            lockManager.acquireExclusive(tid, pid);
        }
        
        p = bufPool.get(pid);
        
        if (p != null) {
           return p;
        }
        
        p = Database.getCatalog().getDatabaseFile(pid.getTableId()).readPage(pid);
        
        // buffer pool is full, evict a page
        while (bufPool.size() >= numPages) {
            evictPage();
        }
        // add page to buffer pool
        bufPool.put(pid, p);
        
        return p;
    }

    /**
     * Releases the lock on a page.
     * Calling this is very risky, and may result in wrong behavior. Think hard
     * about who needs to call this and why, and why they can run the risk of
     * calling it.
     *
     * @param tid the ID of the transaction requesting the unlock
     * @param pid the ID of the page to unlock
     */
    public void releasePage(TransactionId tid, PageId pid) {
        lockManager.release(tid, pid);
    }

    /**
     * Release all locks associated with a given transaction.
     *
     * @param tid the ID of the transaction requesting the unlock
     */
    public void transactionComplete(TransactionId tid) throws IOException {
        transactionComplete(tid, true);
    }

    /** Return true if the specified transaction has a lock on the specified page */
    public boolean holdsLock(TransactionId tid, PageId pid) {
        return lockManager.holdsLock(tid, pid);
    }

    /**
     * Commit or abort a given transaction; release all locks associated to
     * the transaction.
     *
     * @param tid the ID of the transaction requesting the unlock
     * @param commit a flag indicating whether we should commit or abort
     */
    public void transactionComplete(TransactionId tid, boolean commit)
        throws IOException {
        Iterator<Entry<PageId, Page>> pages = bufPool.entrySet().iterator();
        
        while (pages.hasNext()) {
            Entry<PageId, Page> pageEntry = pages.next();
            Page page = pageEntry.getValue();
            PageId pid = pageEntry.getKey();
            
            if (tid.equals(page.isDirty())) {
                if (commit) {
                    Database.getLogFile().logWrite(tid, page.getBeforeImage(), page);
                    Database.getLogFile().force();
                    // use current page contents as the before-image
                    // for the next transaction that modifies this page.
                    page.setBeforeImage();
                } else {
                    // abort, revert changes made by the transaction
                    // by restoring the page to its on-disk state
                    bufPool.put(pid, page.getBeforeImage());
                }
            }
        }
        // release all locks that the transaction held
        lockManager.releaseAll(tid);
    }

    /**
     * Add a tuple to the specified table on behalf of transaction tid.  Will
     * acquire a write lock on the page the tuple is added to and any other 
     * pages that are updated (Lock acquisition is not needed for lab2). 
     * May block if the lock(s) cannot be acquired.
     * 
     * Marks any pages that were dirtied by the operation as dirty by calling
     * their markDirty bit, and adds versions of any pages that have 
     * been dirtied to the cache (replacing any existing versions of those pages) so 
     * that future requests see up-to-date pages. 
     *
     * @param tid the transaction adding the tuple
     * @param tableId the table to add the tuple to
     * @param t the tuple to add
     */
    public void insertTuple(TransactionId tid, int tableId, Tuple t)
        throws DbException, IOException, TransactionAbortedException {
        ArrayList<Page> pages = Database.getCatalog().getDatabaseFile(tableId).insertTuple(tid, t);
        
        for (Page p : pages) {
            p.markDirty(true, tid);
            bufPool.put(p.getId(), p);
        }
    }

    /**
     * Remove the specified tuple from the buffer pool.
     * Will acquire a write lock on the page the tuple is removed from and any
     * other pages that are updated. May block if the lock(s) cannot be acquired.
     *
     * Marks any pages that were dirtied by the operation as dirty by calling
     * their markDirty bit, and adds versions of any pages that have 
     * been dirtied to the cache (replacing any existing versions of those pages) so 
     * that future requests see up-to-date pages. 
     *
     * @param tid the transaction deleting the tuple.
     * @param t the tuple to delete
     */
    public void deleteTuple(TransactionId tid, Tuple t)
        throws DbException, IOException, TransactionAbortedException {
        ArrayList<Page> pages = Database.getCatalog().getDatabaseFile(
                t.getRecordId().getPageId().getTableId()).deleteTuple(tid, t);
        
        for (Page p : pages) {
            p.markDirty(true, tid);
            bufPool.put(p.getId(), p);
        }
    }

    /**
     * Flush all dirty pages to disk.
     * NB: Be careful using this routine -- it writes dirty data to disk so will
     *     break simpledb if running in NO STEAL mode.
     */
    public synchronized void flushAllPages() throws IOException {
        for (Page p : bufPool.values()) {
            flushPage(p.getId());
        }
    }

    /** Remove the specific page id from the buffer pool.
        Needed by the recovery manager to ensure that the
        buffer pool doesn't keep a rolled back page in its
        cache.
        
        Also used by B+ tree files to ensure that deleted pages
        are removed from the cache so they can be reused safely
    */
    public synchronized void discardPage(PageId pid) {
        bufPool.remove(pid);
    }

    /**
     * Flushes a certain page to disk
     * @param pid an ID indicating the page to flush
     */
    private synchronized void flushPage(PageId pid) throws IOException {
        Page p = bufPool.get(pid);
        
        if (p == null) {
            throw new IOException();
        }
        
        // append an update record to the log, with
        // a before-image and after-image.
        TransactionId dirtier = p.isDirty();
        
        if (dirtier != null){
          Database.getLogFile().logWrite(dirtier, p.getBeforeImage(), p);
          Database.getLogFile().force();
        }
        
        Database.getCatalog().getDatabaseFile(pid.getTableId()).writePage(p);
//        p.markDirty(false, null);
    }

    /** Write all pages of the specified transaction to disk.
     */
    public synchronized void flushPages(TransactionId tid) throws IOException {
        Iterator<Entry<PageId, Page>> pages = bufPool.entrySet().iterator();
        
        while (pages.hasNext()) {
            Entry<PageId, Page> pageEntry = pages.next();
            Page page = pageEntry.getValue();
            PageId pid = pageEntry.getKey();
            
            if (tid.equals(page.isDirty())) {
                flushPage(pid);
            }
        }
    }

    /**
     * Discards a page from the buffer pool.
     * Flushes the page to disk to ensure dirty pages are updated on disk.
     */
    private synchronized void evictPage() throws DbException {
         if (!bufPool.isEmpty()) {
             Iterator<PageId> pids = bufPool.keySet().iterator();
             
             while (bufPool.size() >= numPages && pids.hasNext()) {
                 PageId pid = pids.next();
                 
                 // STEAL - can flush any
                 try {
                    flushPage(pid);
                    bufPool.remove(pid);
                    
                 } catch (IOException e) {
                     // TODO Auto-generated catch block
                     e.printStackTrace();
                 }
             }
             // all pages are dirty, throw a DbException
             if (bufPool.size() >= numPages) {
                 throw new DbException("all pages are dirty");
             }
         }
    }
}
