/*
 Copyright 2016 Goldman Sachs.
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
 */

package com.gs.fw.common.mithra.finder;

import com.gs.fw.common.mithra.attribute.NonPrimitiveAttribute;

import java.sql.PreparedStatement;
import java.sql.SQLException;



public class NonPrimitiveNotEqOperation extends AtomicNotEqualityOperation implements SqlParameterSetter
{

    private Object parameter;

    public NonPrimitiveNotEqOperation(NonPrimitiveAttribute attribute, Object parameter)
    {
        super(attribute);
        this.parameter = parameter;
    }

    public NonPrimitiveNotEqOperation()
    {
        // for externalizable
    }

    protected void setParameter(Object parameter)
    {
        this.parameter = parameter;
    }

    protected Boolean matchesWithoutDeleteCheck(Object o)
    {
        Object incoming = this.getAttribute().valueOf(o);
        if (incoming == null) return false; // null is not equal to, or not equal to anything. blame it on the SQL standard
        return !incoming.equals(parameter);
    }

    public int setSqlParameters(PreparedStatement pstmt, int startIndex, SqlQuery query) throws SQLException
    {
        ((NonPrimitiveAttribute)this.getAttribute()).setSqlParameter(startIndex, pstmt, parameter, query.getTimeZone(), query.getDatabaseType());
        return 1;
    }

    public int hashCode()
    {
        return ~(this.getAttribute().hashCode() ^ this.parameter.hashCode());
    }

    public boolean equals(Object obj)
    {
        if (obj == this) return true;
        if (obj instanceof NonPrimitiveNotEqOperation)
        {
            NonPrimitiveNotEqOperation other = (NonPrimitiveNotEqOperation) obj;
            return this.parameter.equals(other.parameter) && this.getAttribute().equals(other.getAttribute());
        }
        return false;
    }

    public Object getParameterAsObject()
    {
        return this.parameter;
    }

    public void zToString(ToStringContext toStringContext)
    {
        this.getAttribute().zAppendToString(toStringContext);
        toStringContext.append("!=");
        toStringContext.append("\"" + this.getParameterAsObject().toString() + "\"");
    }
}
