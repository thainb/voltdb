/* This file is part of VoltDB.
 * Copyright (C) 2008-2012 VoltDB Inc.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.plannodes;

import org.json_voltpatches.JSONException;
import org.json_voltpatches.JSONStringer;
import org.voltdb.expressions.AbstractExpression;
import org.voltdb.expressions.TupleValueExpression;
import org.voltdb.types.PlanNodeType;

public class LimitPlanNode extends AbstractPlanNode {

    public enum Members {
        OFFSET,
        LIMIT,
        OFFSET_PARAM_IDX,
        LIMIT_PARAM_IDX,
        LIMIT_EXPRESSION;
    }

    protected int m_offset = 0;
    protected int m_limit = -1;

    // -1 also interpreted by EE as uninitialized
    private long m_limitParameterId = -1;
    private long m_offsetParameterId = -1;

    private AbstractExpression m_limitExpression = null;

    public LimitPlanNode() {
        super();
    }

    @Override
    public PlanNodeType getPlanNodeType() {
        return PlanNodeType.LIMIT;
    }

    @Override
    public void validate() throws Exception {
        super.validate();

        // Limit Amount
        if (m_limit < 0) {
            throw new Exception("ERROR: The limit size is negative [" + m_limit + "]");
        } else if (m_offset < 0) {
            throw new Exception("ERROR: The offset amount  is negative [" + m_offset + "]");
        }

        if (m_limitExpression != null) {
            m_limitExpression.validate();
        }
    }

    /**
     * Accessor for flag marking the plan as guaranteeing an identical result/effect
     * when "replayed" against the same database state, such as during replication or CL recovery.
     * @return true only if child is order-deterministic
     */
    @Override
    public boolean isContentDeterministic() {
        boolean precondition = m_children.get(0).isOrderDeterministic();
        if (precondition) {
            return true;
        }
        m_nondeterminismDetail = "a limit on unordered content may return different rows: " +
            m_children.get(0).nondeterminismDetail();
        return false;
    }

    /**
     * @return the limit
     */
    public int getLimit() {
        return m_limit;
    }
    /**
     * @param limit the limit to set
     */
    public void setLimit(int limit) {
        m_limit = limit;
    }
    /**
     * @return the offset
     */
    public int getOffset() {
        return m_offset;
    }
    /**
     * @param offset the offset to set
     */
    public void setOffset(int offset) {
        m_offset = offset;
    }

    public AbstractExpression getLimitExpression() {
        return m_limitExpression;
    }

    public void setLimitExpression(AbstractExpression expr) {
        m_limitExpression = expr;
    }

    @Override
    public void toJSONString(JSONStringer stringer) throws JSONException {
        super.toJSONString(stringer);
        stringer.key(Members.OFFSET.name()).value(m_offset);
        stringer.key(Members.LIMIT.name()).value(m_limit);
        stringer.key(Members.OFFSET_PARAM_IDX.name()).value(m_offsetParameterId);
        stringer.key(Members.LIMIT_PARAM_IDX.name()).value(m_limitParameterId);
        stringer.key(Members.LIMIT_EXPRESSION.name()).value(m_limitExpression);
    }

    public void setLimitParameterIndex(long limitParameterId) {
        m_limitParameterId = limitParameterId;
    }

    public void setOffsetParameterIndex(long offsetParameterId) {
        m_offsetParameterId = offsetParameterId;
    }

    @Override
    public void resolveColumnIndexes()
    {
        // Need to order and resolve indexes of output columns
        assert(m_children.size() == 1);
        m_children.get(0).resolveColumnIndexes();
        NodeSchema input_schema = m_children.get(0).getOutputSchema();
        for (SchemaColumn col : m_outputSchema.getColumns())
        {
            // At this point, they'd better all be TVEs.
            assert(col.getExpression() instanceof TupleValueExpression);
            TupleValueExpression tve = (TupleValueExpression)col.getExpression();
            int index = input_schema.getIndexOfTve(tve);
            tve.setColumnIndex(index);
        }
        m_outputSchema.sortByTveIndex();
    }

    @Override
    protected String explainPlanForNode(String indent) {
        String retval = "";
        if (m_limit >= 0)
            retval += "LIMIT " + String.valueOf(m_limit) + " ";
        if (m_offset > 0)
            retval += "OFFSET " + String.valueOf(m_offset) + " ";
        if (retval.length() > 0) {
            // remove the last space
            return retval.substring(0, retval.length() - 1);
        }
        else {
            return "LIMIT with parameter";
        }
    }
}
