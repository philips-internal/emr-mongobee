package com.github.mongobee.dao;

import java.util.Date;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.mongobee.changeset.ChangeEntry;
import com.github.mongobee.exception.MongobeeConfigurationException;
import com.github.mongobee.exception.MongobeeConnectionException;
import com.github.mongobee.exception.MongobeeLockException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

/**
 * @author lstolowski
 * @since 27/07/2014
 */
public class ChangeEntryDao {
    private static final Logger logger = LoggerFactory.getLogger("Mongobee dao");

    private MongoDatabase mongoDatabase;
    private MongoClient mongoClient;
    private ChangeEntryIndexDao indexDao;
    private String changelogCollectionName;
    private boolean waitForLock;
    private long changeLogLockWaitTime;
    private long changeLogLockPollRate;
    private boolean throwExceptionIfCannotObtainLock;

    private LockDao lockDao;

    public ChangeEntryDao(String changelogCollectionName, String lockCollectionName, boolean waitForLock, long changeLogLockWaitTime,
            long changeLogLockPollRate, boolean throwExceptionIfCannotObtainLock) {
        this.indexDao = new ChangeEntryIndexDao(changelogCollectionName);
        this.lockDao = new LockDao(lockCollectionName);
        this.changelogCollectionName = changelogCollectionName;
        this.waitForLock = waitForLock;
        this.changeLogLockWaitTime = changeLogLockWaitTime;
        this.changeLogLockPollRate = changeLogLockPollRate;
        this.throwExceptionIfCannotObtainLock = throwExceptionIfCannotObtainLock;
    }

    public MongoDatabase getMongoDatabase() {
        return mongoDatabase;
    }

    public MongoDatabase connectMongoDb(MongoClient mongo, String dbName) throws MongobeeConfigurationException {
        this.mongoClient = mongo;

        mongoDatabase = mongo.getDatabase(dbName);

        ensureChangeLogCollectionIndex(mongoDatabase.getCollection(changelogCollectionName));
        initializeLock();
        return mongoDatabase;
    }

    public MongoDatabase connectMongoDb(String database)
            throws MongobeeConfigurationException, MongobeeConnectionException {
        return this.connectMongoDb(mongoClient, database);
    }

    /**
     * Try to acquire process lock
     *
     * @return true if successfully acquired, false otherwise
     * @throws MongobeeConnectionException exception
     * @throws MongobeeLockException exception
     */
    public boolean acquireProcessLock() throws MongobeeConnectionException, MongobeeLockException {
        verifyDbConnection();
        boolean acquired = lockDao.acquireLock(getMongoDatabase());

        if (!acquired && waitForLock) {
            final long timeToGiveUp = new Date().getTime() + (changeLogLockWaitTime * 1000 * 60);
            while (!acquired && (new Date().getTime() < timeToGiveUp)) {
                acquired = lockDao.acquireLock(getMongoDatabase());
                if (!acquired) {
                    logger.info("Waiting for changelog lock....");
                    try {
                        Thread.sleep(changeLogLockPollRate * 1000);
                    } catch (final InterruptedException e) {
                        // nothing
                    }
                }
            }
        }

        if (!acquired && throwExceptionIfCannotObtainLock) {
            logger.info("Mongobee did not acquire process lock. Throwing exception.");
            throw new MongobeeLockException("Could not acquire process lock");
        }

        return acquired;
    }

    public void releaseProcessLock() throws MongobeeConnectionException {
        verifyDbConnection();
        lockDao.releaseLock(getMongoDatabase());
    }

    public boolean isProccessLockHeld() throws MongobeeConnectionException {
        verifyDbConnection();
        return lockDao.isLockHeld(getMongoDatabase());
    }

    public boolean isNewChange(ChangeEntry changeEntry) throws MongobeeConnectionException {
        verifyDbConnection();

        final MongoCollection<Document> mongobeeChangeLog = getMongoDatabase().getCollection(changelogCollectionName);
        final Document entry = mongobeeChangeLog.find(changeEntry.buildSearchQueryDBObject()).first();

        return entry == null;
    }

    public void save(ChangeEntry changeEntry) throws MongobeeConnectionException {
        verifyDbConnection();

        final MongoCollection<Document> mongobeeLog = getMongoDatabase().getCollection(changelogCollectionName);

        mongobeeLog.insertOne(changeEntry.buildFullDBObject());
    }

    private void verifyDbConnection() throws MongobeeConnectionException {
        if (getMongoDatabase() == null) {
            throw new MongobeeConnectionException("Database is not connected. Mongobee has thrown an unexpected error",
                    new NullPointerException());
        }
    }

    private void ensureChangeLogCollectionIndex(MongoCollection<Document> collection) {
        final Document index = indexDao.findRequiredChangeAndAuthorIndex(mongoDatabase);
        if (index == null) {
            indexDao.createRequiredUniqueIndex(collection);
            logger.debug("Index in collection " + changelogCollectionName + " was created");
        } else if (!indexDao.isUnique(index)) {
            indexDao.dropIndex(collection, index);
            indexDao.createRequiredUniqueIndex(collection);
            logger.debug("Index in collection " + changelogCollectionName + " was recreated");
        }

    }

    public void close() {
        this.mongoClient.close();
    }

    private void initializeLock() {
        lockDao.intitializeLock(mongoDatabase);
    }

    public void setIndexDao(ChangeEntryIndexDao changeEntryIndexDao) {
        this.indexDao = changeEntryIndexDao;
    }

    /* Visible for testing */
    void setLockDao(LockDao lockDao) {
        this.lockDao = lockDao;
    }

    public void setChangelogCollectionName(String changelogCollectionName) {
        this.indexDao.setChangelogCollectionName(changelogCollectionName);
        this.changelogCollectionName = changelogCollectionName;
    }

    public void setLockCollectionName(String lockCollectionName) {
        this.lockDao.setLockCollectionName(lockCollectionName);
    }

    public boolean isWaitForLock() {
        return waitForLock;
    }

    public void setWaitForLock(boolean waitForLock) {
        this.waitForLock = waitForLock;
    }

    public long getChangeLogLockWaitTime() {
        return changeLogLockWaitTime;
    }

    public void setChangeLogLockWaitTime(long changeLogLockWaitTime) {
        this.changeLogLockWaitTime = changeLogLockWaitTime;
    }

    public long getChangeLogLockPollRate() {
        return changeLogLockPollRate;
    }

    public void setChangeLogLockPollRate(long changeLogLockPollRate) {
        this.changeLogLockPollRate = changeLogLockPollRate;
    }

    public boolean isThrowExceptionIfCannotObtainLock() {
        return throwExceptionIfCannotObtainLock;
    }

    public void setThrowExceptionIfCannotObtainLock(boolean throwExceptionIfCannotObtainLock) {
        this.throwExceptionIfCannotObtainLock = throwExceptionIfCannotObtainLock;
    }

}
