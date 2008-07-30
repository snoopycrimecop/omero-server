/*
 *   Copyright 2008 Glencoe Software, Inc. All rights reserved.
 *   Use is subject to license terms supplied in LICENSE.txt
 */

package ome.services.sharing;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ome.api.IShare;
import ome.conditions.OptimisticLockException;
import ome.model.IObject;
import ome.model.meta.Share;
import ome.services.sharing.data.ShareData;
import ome.services.sharing.data.ShareItem;

import org.hibernate.HibernateException;
import org.hibernate.Query;
import org.hibernate.Session;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.orm.hibernate3.HibernateCallback;
import org.springframework.orm.hibernate3.HibernateTemplate;
import org.springframework.util.Assert;

import Ice.ReadObjectCallback;

/**
 * 
 * 
 * @author Josh Moore, josh at glencoesoftware.com
 * @since 3.0-Beta4
 * @see IShare
 */
public class BlobShareStore extends ShareStore {

    /**
     * HibernateTemplate used to query and update the store during normal
     * operation.
     */
    final protected HibernateTemplate ht;

    // Initialization/Destruction
    // =========================================================================

    /**
     * 
     */
    public BlobShareStore(HibernateTemplate ht) {
        Assert.notNull(ht);
        this.ht = ht;
    }

    @Override
    public void doInit() {
        // Currently nothing.
    }

    // Overrides
    // =========================================================================

    @Override
    public int totalShares() {
        return -1;
    }

    @Override
    public int totalSharedItems() {
        return -1;
    }

    @Override
    public void doSet(Share share, ShareData data, List<ShareItem> items) {
        Ice.OutputStream os = Ice.Util.createOutputStream(ic);
        byte[] bytes = null;
        try {
            os.writeObject(data);
            os.writePendingObjects();
            bytes = os.finished();
        } finally {
            os.destroy();
        }

        long oldOptLock = data.optlock;
        long newOptLock = oldOptLock + 1;

        try {
            ht.find(
                    "select s from Share s where s.id = " + data.id
                            + " and s.version =" + data.optlock).get(0);
        } catch (IndexOutOfBoundsException ioobe) {
            throw new OptimisticLockException("Share " + data.id
                    + " has been updated by someone else.");
        }

        share.setActive(data.enabled);
        share.setData(bytes);
        share.setItemCount((long) items.size());
        share.setVersion((int) newOptLock);
        ht.merge(share);

    }

    @Override
    public ShareData get(final long id) {
        Share s = (Share) ht.get(Share.class, id);
        if (s == null) {
            return null;
        }
        byte[] data = s.getData();
        return parse(data);
    }

    @Override
    public List<ShareData> getShares(boolean active) {
        List<ShareData> rv = new ArrayList<ShareData>();
        try {
            List<byte[]> data = data();
            for (byte[] bs : data) {
                ShareData d = parse(bs);
                if (active && !d.enabled) {
                    continue;
                }
                rv.add(parse(bs));
            }
            return rv;
        } catch (EmptyResultDataAccessException empty) {
            return null;
        }
    }

    @Override
    public List<ShareData> getShares(long userId, boolean own, boolean active) {
        List<ShareData> rv = new ArrayList<ShareData>();
        try {
            List<byte[]> data = data();
            for (byte[] bs : data) {
                ShareData d = parse(bs);
                if (active && !d.enabled) {
                    continue;
                }
                if (own) {
                    if (d.owner != userId) {
                        continue;
                    }
                } else {
                    if (!d.members.contains(userId)) {
                        continue;
                    }
                }
                rv.add(parse(bs));
            }
            return rv;
        } catch (EmptyResultDataAccessException empty) {
            return null;
        }
    }

    @Override
    public <T extends IObject> boolean doContains(long sessionId, Class<T> kls,
            long objId) {
        ShareData data = get(sessionId);
        List<Long> ids = data.objectMap.get(kls.getName());
        return ids.contains(objId);
    }

    @Override
    public void doClose() {
        // no-op
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<Long> keys() {
        return new HashSet<Long>(ht.executeFind(new HibernateCallback() {
            public Object doInHibernate(Session session)
                    throws HibernateException, SQLException {
                Query q = session.createQuery("select id from Share");
                return q.list();
            }
        }));
    }

    // Helpers
    // =========================================================================

    private ShareData parse(byte[] data) {

        if (data == null) {
            return null; // EARLY EXIT!
        }

        Ice.InputStream is = Ice.Util.createInputStream(ic, data);
        final ShareData[] shareData = new ShareData[1];
        try {
            is.readObject(new ReadObjectCallback() {

                public void invoke(Ice.Object arg0) {
                    shareData[0] = (ShareData) arg0;
                }
            });
            is.readPendingObjects();
        } finally {
            is.destroy();
        }
        return shareData[0];
    }

    /**
     * Returns a list of data from all shares.
     * 
     * @return
     */
    @SuppressWarnings("unchecked")
    private List<byte[]> data() {
        List<byte[]> data = ht.executeFind(new HibernateCallback() {
            public Object doInHibernate(Session session)
                    throws HibernateException, SQLException {
                return session.createQuery("select data from Share").list();
            }
        });
        return data;
    }

}