package ome.services.query;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;

import org.hibernate.Criteria;
import org.hibernate.HibernateException;
import org.hibernate.Session;
import org.hibernate.criterion.Restrictions;

import ome.model.core.Image;

public class PojosFindHierarchiesQueryDefinition extends Query
{
    

    public PojosFindHierarchiesQueryDefinition(QueryParameter... parameters)
    {
        super(parameters);
    }  

    @Override
    protected void defineParameters()
    {
        defs = new QueryParameterDef[] {
                new QueryParameterDef(QP.CLASS, Class.class, false),
                new QueryParameterDef(QP.IDS, Collection.class, false),
                new QueryParameterDef(QP.OPTIONS, Map.class, true) };
    }

    @Override
    protected Object runQuery(Session session) throws HibernateException, SQLException
    {
        Criteria c = session.createCriteria(Image.class);
        c.setResultTransformer(Criteria.DISTINCT_ROOT_ENTITY);
        c.add(Restrictions.in("id",(Collection) value(QP.IDS)));
        Hierarchy.fetchParents(c,(Class) value(QP.CLASS),Integer.MAX_VALUE);
        
        return c.list();
        
    }

}
//select i from Image i
//#bottomUpHierarchy()
//    where 
//#imagelist()
//#filters()
//#typeExperimenter()
