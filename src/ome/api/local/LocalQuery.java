/*
 * ome.api.local.LocalQuery
 *
 *------------------------------------------------------------------------------
 *
 *  Copyright (C) 2005 Open Microscopy Environment
 *      Massachusetts Institute of Technology,
 *      National Institutes of Health,
 *      University of Dundee
 *
 *
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation; either
 *    version 2.1 of the License, or (at your option) any later version.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public
 *    License along with this library; if not, write to the Free Software
 *    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 *------------------------------------------------------------------------------
 */

package ome.api.local;

// Java imports

// Third-party libraries

// Application-internal dependencies
import ome.services.query.Query;

/**
 * Provides local (internal) extensions for querying 
 * 
 * @author <br>
 *         Josh Moore &nbsp;&nbsp;&nbsp;&nbsp; <a
 *         href="mailto:josh.moore@gmx.de"> josh.moore@gmx.de</a>
 * @version 1.0 <small> (<b>Internal version:</b> $Revision: $ $Date: $)
 *          </small>
 * @since OMERO3.0
 */
public interface LocalQuery extends ome.api.IQuery {

    /** 
     * Executes a locally defined Query. Currently a thin
     * wrapper around Spring's HibernateTemplate 
     * 
     * @param query
     *      An implementation of the HibernateCallback interface.
     * @return result of the query 
     *      See document for the query for the return type.
     * 
     * 
     * @see org.springframework.orm.hibernate3.HibernateTemplate
     * @see org.springframework.orm.hibernate3.HibernateCallback
     */
    Object execute(Query query);

    /** Removes an object graph from the session. This allows for
     * non-permanent, mutable calls on the graph.
     * @param object
     */
    void evict(Object object);

    /** Uses the Hibernate static method <code>initialize</code> to prepare
     * an object for shipping over the wire.
     * 
     * It is better to do this in your queries.
     * 
     * @param object
     * @see org.hibernate.Hibernate
     */
    void initialize(Object object);
    
    /** 
     * Checks if a type has been mapped in Hibernate.
     * @param type
     *      String representation of a full-qualified Hibernate-mapped type.
     * @return yes or no.
     */ 
    boolean checkType(String type);

    /**
     * Checks if a property is defined on a mapped Hibernate type.
     * @param type
     *      String representation of a full-qualified Hibernate-mapped type.
     * @param property
     *      Property as defined in Hibernate 
     *      NOT the public final static Strings on our IObject classes. 
     * @return yes or no.
     */
    boolean checkProperty(String type, String property);

}
