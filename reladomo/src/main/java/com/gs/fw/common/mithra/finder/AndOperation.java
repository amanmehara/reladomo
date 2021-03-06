
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

import com.gs.fw.common.mithra.MithraManagerProvider;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.gs.collections.impl.list.mutable.FastList;
import com.gs.fw.common.mithra.MithraObjectPortal;
import com.gs.fw.common.mithra.attribute.AsOfAttribute;
import com.gs.fw.common.mithra.attribute.Attribute;
import com.gs.fw.common.mithra.attribute.TimestampAttribute;
import com.gs.fw.common.mithra.notification.MithraDatabaseIdentifierExtractor;
import com.gs.fw.common.mithra.util.*;

public class AndOperation implements Operation
{
    private final InternalList operands;
    private transient volatile boolean combined = false;
    private static final OperationEfficiencyComparator EFFICIENCY_COMPARATOR = new OperationEfficiencyComparator();

    public AndOperation(com.gs.fw.finder.Operation left, com.gs.fw.finder.Operation right)
    {
        this.operands = new InternalList(3);
        this.addOperand(left);
        this.addOperand(right);
    }

    private AndOperation(InternalList operands)
    {
        this.operands = operands;
    }

    private void addOperand(com.gs.fw.finder.Operation operand)
    {
        if (operand instanceof AndOperation)
        {
            operands.addAll(((AndOperation) operand).getOperands());
        }
        else
        {
            operands.add(operand);
        }
        combined = false;
    }

    private int addOperandAndRemoveCurrent(com.gs.fw.finder.Operation operand, int index)
    {
        if (operand instanceof AndOperation)
        {
            operands.remove(index);
            InternalList otherOperands = ((AndOperation) operand).getOperands();
            operands.addAll(index, otherOperands);
            return otherOperands.size();
        }
        else
        {
            operands.set(index, operand); // implicit remove
            return 1;
        }
    }

    public boolean usesUniqueIndex()
    {
        this.combineOperands();
        boolean result = false;
        for (int i = 0; i < operands.size(); i++)
        {
            Operation op = (Operation) operands.get(i);
            if (op.usesUniqueIndex())
            {
                result = true;
            }
            if (op instanceof MappedOperation && !op.usesUniqueIndex())
            {
                result = false;
                break;
            }
        }
        return result;
    }

    public boolean usesImmutableUniqueIndex()
    {
        this.combineOperands();
        for (int i = 0; i < operands.size(); i++)
        {
            Operation op = (Operation) operands.get(i);
            if (op.usesImmutableUniqueIndex()) return true;
        }
        return false;
    }

    public boolean usesNonUniqueIndex()
    {
        this.combineOperands();
        boolean result = false;
        for (int i = 0; i < operands.size(); i++)
        {
            Operation op = (Operation) operands.get(i);
            if (op.usesNonUniqueIndex() || op.usesUniqueIndex())
            {
                result = true;
            }
            if (op instanceof MappedOperation && !(op.usesUniqueIndex() || op.usesNonUniqueIndex()))
            {
                result = false;
                break;
            }
        }
        return result;
    }

    public int zEstimateReturnSize()
    {
        this.combineOperands();
        return (int) (((Operation) this.operands.get(0)).zEstimateReturnSize() * Math.pow(0.9, this.operands.size() - 1));
    }

    @Override
    public int zEstimateMaxReturnSize()
    {
        this.combineOperands();
        return ((Operation) this.operands.get(0)).zEstimateMaxReturnSize();
    }

    @Override
    public boolean zIsEstimatable()
    {
        this.combineOperands();
        return ((Operation) this.operands.get(0)).zIsEstimatable();
    }

    public void zRegisterEqualitiesAndAtomicOperations(TransitivePropagator transitivePropagator)
    {
        for (int i = 0; i < operands.size(); i++)
        {
            Operation op = (Operation) operands.get(i);
            op.zRegisterEqualitiesAndAtomicOperations(transitivePropagator);
        }
    }

    public boolean zHazTriangleJoins()
    {
        this.combineOperands();
        for (int i = 0; i < operands.size(); i++)
        {
            Operation op = (Operation) operands.get(i);
            if (op.zHazTriangleJoins()) return true;
        }
        return false;
    }

    public void zToString(ToStringContext toStringContext)
    {
        this.combineOperands();
        for (int i = 0; i < operands.size(); i++)
        {
            Operation op = (Operation) operands.get(i);
            toStringContext.beginAnd();
            op.zToString(toStringContext);
            toStringContext.endAnd();
        }
    }

    public EqualityOperation zExtractEqualityOperations()
    {
        combineOperands();
        for (int i = 0; i < this.operands.size(); i++)
        {
            Operation operation = ((Operation) operands.get(i));
            EqualityOperation result = operation.zExtractEqualityOperations();
            if (result != null)
            {
                return result;
            }
        }
        return null;
    }

    public List applyOperationToFullCache()
    {
        combineOperands();
        int appliedOperation = -1;
        List result = null;
        for (int i = 0; i < this.operands.size(); i++)
        {
            Operation operation = ((Operation) operands.get(i));
            result = operation.applyOperationToFullCache();
            if (result != null)
            {
                appliedOperation = i;
                break;
            }
        }
        if (result != null)
        {
            result = applyOperationExcept(appliedOperation, result);
        }
        else if (!MithraManagerProvider.getMithraManager().isInTransaction())
        {
            throw new RuntimeException("could not resolve against full cache");
        }
        return result;
    }

    private List applyOperationExcept(int appliedOperation, List result)
    {
        if (MithraCpuBoundThreadPool.isParallelizable(result.size()))
        {
            result = applyToLargeResultsInParallel(appliedOperation, result);
        }
        else if (result.size() > 100 && operands.size() > 2)
        {
            result = applyToLargeResults(appliedOperation, result);
        }
        else
        {
            for (int i = 0; i < operands.size() && result != null && result.size() > 0; i++)
            {
                if (i != appliedOperation) result = ((Operation) operands.get(i)).applyOperation(result);
            }
        }
        return result;
    }

    public static List applyAtomicOperationsInParallel(List result, final Operation[] atomic)
    {
        MithraCpuBoundThreadPool pool = MithraCpuBoundThreadPool.getInstance();
        List<List> lists = pool.split(result);
        int chunks = lists.size();
        CpuBoundTask[] tasks = new CpuBoundTask[chunks];
        final List[] localResults = new List[chunks];
        for (int i = 0; i < chunks; i++)
        {
            final List sublist = lists.get(i);
            final int chunk = i;
            tasks[i] = new CpuBoundTask()
            {
                @Override
                public void execute()
                {
                    localResults[chunk] = applyAtomic(sublist, atomic);
                }
            };
        }
        new FixedCountTaskFactory(tasks).startAndWorkUntilFinished();
        result = new MithraCompositeList(localResults.length);
        for (List localResult : localResults)
        {
            if (localResult == null)
            {
                return null;
            }
            result.addAll(localResult);
        }
        return result;
    }

    private List applyToLargeResultsInParallel(int appliedOperation, List result)
    {
        final InternalList complexList = new InternalList(operands.size());
        final InternalList parallelComplexList = new InternalList(operands.size());
        final InternalList atomicList = new InternalList(operands.size());
        separateComplexAndAtomic(appliedOperation, complexList, atomicList, parallelComplexList);
        final Operation[] atomic = new Operation[atomicList.size()];
        atomicList.toArray(atomic);
        final Operation[] complex = new Operation[complexList.size()];
        complexList.toArray(complex);
        MithraCpuBoundThreadPool pool = MithraCpuBoundThreadPool.getInstance();
        List<List> lists = pool.split(result);
        int chunks = lists.size();
        CpuBoundTask[] tasks = new CpuBoundTask[chunks];
        final List[] localResults = new List[chunks];
        for (int i = 0; i < chunks; i++)
        {
            final List sublist = lists.get(i);
            final int chunk = i;
            tasks[i] = new CpuBoundTask()
            {
                @Override
                public void execute()
                {
                    localResults[chunk] = applyAtomicAndComplex(sublist, atomic, complexList);
                }
            };
        }
        new FixedCountTaskFactory(tasks).startAndWorkUntilFinished();
        List finalResult = new MithraCompositeList(localResults.length);
        for (List localResult : localResults)
        {
            if (localResult == null)
            {
                return null;
            }
            finalResult.addAll(localResult);
        }
        if (parallelComplexList.size() > 0)
        {
            for (int i = 0; i < parallelComplexList.size() && finalResult != null && finalResult.size() > 0; i++)
            {
                finalResult = ((Operation)parallelComplexList.get(i)).applyOperation(finalResult);
            }
        }
        return finalResult;
    }

    private static List applyAtomicNotInPlace(List result, Operation[] atomic)
    {
        MithraFastList newResults = new MithraFastList(result.size());
        for (int i = 0; i < result.size(); i++)
        {
            Object o = result.get(i);
            Boolean matched = Boolean.TRUE;
            for (int j = 0; j < atomic.length && matched; j++)
            {
                Operation op = atomic[j];
                matched = op.matches(o);
                if (matched == null) return null;
            }
            if (matched)
            {
                newResults.add(o);
            }
        }
        return newResults;
    }

    private static List applyAtomicInPlace(FastList result, Operation[] atomic)
    {
        int currentFilledIndex = 0;
        for (int i = 0; i < result.size(); i++)
        {
            Object o = result.get(i);
            Boolean matched = Boolean.TRUE;
            for (int j = 0; j < atomic.length && matched; j++)
            {
                Operation op = atomic[j];
                matched = op.matches(o);
                if (matched == null) return null;
            }
            if (matched)
            {
                // keep it
                if (currentFilledIndex != i)
                {
                    result.set(currentFilledIndex, o);
                }
                currentFilledIndex++;
            }
        }
        resetTheEnd(result, currentFilledIndex);
        return result;
    }

    private static void resetTheEnd(List list, final int newCurrentFilledIndex)
    {
        int initialSize = list.size();
        for (int i = newCurrentFilledIndex; i < initialSize; i++)
        {
            list.remove(list.size() - 1);
        }
    }

    private List applyAtomicAndComplex(List sublist, Operation[] atomic, InternalList complexList)
    {
        List result = applyAtomic(sublist, atomic);
        for (int i = 0; i < complexList.size() && result != null && result.size() > 0; i++)
        {
            result = ((Operation)complexList.get(i)).applyOperation(result);
        }
        return result;
    }

    private static List applyAtomic(List result, Operation[] atomic)
    {
        if (atomic.length > 0)
        {
            if (result instanceof FastList)
            {
                result = applyAtomicInPlace((FastList) result, atomic);
            }
            else result = applyAtomicNotInPlace(result, atomic);
        }
        return result;
    }

    private List applyToLargeResults(int appliedOperation, List result)
    {
        InternalList complex = new InternalList(operands.size() - 1);
        InternalList atomic = new InternalList(operands.size() - 1);
        separateComplexAndAtomic(appliedOperation, complex, atomic, complex);
        if (atomic.size() > 1)
        {
            int size = result.size();
            MithraFastList newResults = new MithraFastList(size);
            for (int i = 0; i < size; i++)
            {
                Object o = result.get(i);
                Boolean matched = Boolean.TRUE;
                for (int j = 0; j < atomic.size() && matched; j++)
                {
                    Operation op = (Operation) atomic.get(j);
                    matched = op.matches(o);
                    if (matched == null) return null;
                }
                if (matched)
                {
                    newResults.add(o);
                }
            }
            result = newResults;
        }
        else if (atomic.size() == 1)
        {
            result = ((Operation) atomic.get(0)).applyOperation(result);
        }
        for (int i = 0; i < complex.size() && result != null && result.size() > 0; i++)
        {
            result = ((Operation) complex.get(i)).applyOperation(result);
        }
        return result;
    }

    private void separateComplexAndAtomic(int appliedOperation, InternalList complex, InternalList atomic, InternalList parallelComplexList)
    {
        for (int i = 0; i < operands.size(); i++)
        {
            if (i != appliedOperation)
            {
                Operation o = (Operation) operands.get(i);
                if (o.zPrefersBulkMatching())
                {
                    if (o.zHasParallelApply())
                    {
                        parallelComplexList.add(o);
                    }
                    else
                    {
                        complex.add(o);
                    }
                }
                else
                {
                    atomic.add(o);
                }
            }
        }
    }

    public List applyOperationToPartialCache()
    {
        this.combineOperands();
        int appliedOperation = -1;
        List result = null;
        for (int i = 0; i < this.operands.size(); i++)
        {
            Operation operation = ((Operation) operands.get(i));
            result = operation.getResultObjectPortal().zFindInMemoryWithoutAnalysis(operation, true);
            if (result != null)
            {
                appliedOperation = i;
                break;
            }
        }
        if (result != null)
        {
            for (int i = 0; i < operands.size() && result != null && result.size() > 0; i++)
            {
                if (i != appliedOperation) result = ((Operation) operands.get(i)).applyOperation(result);
            }
        }
        return result;
    }

    protected void combineOperands()
    {
        if (!this.combined)
        {
            // todo: rezaem: check for operator compatibility
            synchronized (this)
            {
                int oldSize;
                do
                {
                    oldSize = operands.size();
                    for (int i = 0; i < operands.size(); i++)
                    {
                        Operation op = (Operation) operands.get(i);
                        for (int j = i + 1; j < operands.size(); )
                        {
                            Operation otherOp = (Operation) operands.get(j);
                            Operation combinedOp = op.zCombinedAnd(otherOp);
                            if (combinedOp != null)
                            {
                                operands.removeByReplacingFromEnd(j);
                                if (op != combinedOp)
                                {
                                    j += this.addOperandAndRemoveCurrent(combinedOp, i) - 1;
                                    op = (Operation) operands.get(i);
                                }
                            }
                            else j++;
                        }
                    }
                } while (oldSize > operands.size());

                InternalList mappedOps = new InternalList(this.operands.size());
                InternalList equalityOps = new InternalList(this.operands.size());
                for (int i = 0; i < operands.size(); )
                {
                    Operation op = (Operation) operands.get(i);
                    if (op instanceof MappedOperation)
                    {
                        mappedOps.add(op);
                        operands.removeByReplacingFromEnd(i);
                    }
                    else
                    {
                        i++;
                        if (op instanceof AtomicEqualityOperation || op instanceof MultiEqualityOperation)
                        {
                            equalityOps.add(op);
                        }
                    }
                }
                optimizeMappedOperations(mappedOps, equalityOps);
                this.operands.addAll(mappedOps);
                operands.sort(EFFICIENCY_COMPARATOR);
            }
            this.combined = true;
        }
    }

    private void optimizeMappedOperations(InternalList mappedOps, InternalList equalityOps)
    {

        for (int i = 0; i < mappedOps.size(); i++)
        {
            MappedOperation mappedOperation = (MappedOperation) mappedOps.get(i);
            for (int j = 0; j < equalityOps.size(); j++)
            {
                Operation op = (Operation) equalityOps.get(j);
                MappedOperation substituted = mappedOperation.equalitySubstitute(op);
                if (substituted != null)
                {
                    mappedOperation = substituted;
                    mappedOps.set(i, mappedOperation);
                }
            }
        }
    }

    public List applyOperation(List list)
    {
        combineOperands();
        return applyOperationExcept(-1, list);
    }

    private InternalList getOperands()
    {
        return operands;
    }

    public Operation or(com.gs.fw.finder.Operation op)
    {
        return OrOperation.or(this, op);
    }

    public Operation and(com.gs.fw.finder.Operation op)
    {
        if (op == NoOperation.instance())
        {
            return this;
        }
        if (op instanceof AndOperation)
        {
            AndOperation other = (AndOperation) op;
            InternalList newOperands = new InternalList(this.operands.size() + other.operands.size());
            AndOperation result = new AndOperation(newOperands);
            newOperands.addAll(this.operands);
            for (int i = 0; i < other.operands.size(); i++)
            {
                if (!this.operands.contains(other.operands.get(i)))
                {
                    result.addOperand((Operation) other.operands.get(i));
                }
            }
            return result;
        }
        if (this.operands.contains(op)) return this; // no need to add again.
        InternalList newOperands = new InternalList(this.operands.size() + 1);
        newOperands.addAll(this.operands);
        AndOperation result = new AndOperation(newOperands);
        result.addOperand(op);
        return result;
    }

    public MithraObjectPortal getResultObjectPortal()
    {
        return ((Operation) operands.get(0)).getResultObjectPortal();
    }

    public String zGetResultClassName()
    {
        return ((Operation) operands.get(0)).zGetResultClassName();
    }

    public boolean zIsNone()
    {
        this.combineOperands();
        for (int i = 0; i < operands.size(); i++)
        {
            Operation op = ((Operation) operands.get(i));
            if (op.zIsNone()) return true;
        }
        return false;
    }

    public void zAddAllLeftAttributes(Set<Attribute> result)
    {
        for (int i = 0; i < operands.size(); i++)
        {
            ((Operation) operands.get(i)).zAddAllLeftAttributes(result);
        }
    }

    public Operation zSubstituteForTempJoin(Map<Attribute, Attribute> attributeMap, Object prototypeObject)
    {
        InternalList newOps = new InternalList(this.operands.size());
        for (int i = 0; i < operands.size(); i++)
        {
            Operation op = ((Operation) operands.get(i));
            Operation op2 = op.zSubstituteForTempJoin(attributeMap, prototypeObject);
            if (op2 != null) newOps.add(op2);
        }
        if (newOps.size() == 0) return null;
        if (newOps.size() == 1) return (Operation) newOps.get(0);
        return new AndOperation(newOps);
    }

    public Operation zGetAsOfOp(AsOfAttribute asOfAttribute)
    {
        this.combineOperands();
        for (int i = 0; i < operands.size(); i++)
        {
            Operation op = ((Operation) operands.get(i));
            Operation result = op.zGetAsOfOp(asOfAttribute);
            if (result != null) return result;
        }
        return null;
    }

    public void generateSql(SqlQuery query)
    {
        this.combineOperands();
        for (int i = 0; i < operands.size(); i++)
        {
            Operation op = ((Operation) operands.get(i));
            boolean addedAnd = query.beginAnd();
            op.generateSql(query);
            query.endAnd(addedAnd);
        }
    }

    public int getClauseCount(SqlQuery query)
    {
        this.combineOperands();
        int count = 0;
        for (int i = 0; i < operands.size(); i++)
        {
            Operation op = ((Operation) operands.get(i));
            count += op.getClauseCount(query);
        }
        return count;
    }

    public void registerAsOfAttributesAndOperations(AsOfEqualityChecker checker)
    {
        this.combineOperands();
        for (int i = 0; i < operands.size(); i++)
        {
            Operation op = ((Operation) operands.get(i));
            op.registerAsOfAttributesAndOperations(checker);
        }
    }

    public Operation insertAsOfEqOperation(AtomicOperation[] asOfEqOperations, MapperStackImpl insertPosition, AsOfEqualityChecker stack)
    {
        if (insertPosition.equals(stack.getCurrentMapperList()))
        {
            return this.zInsertAsOfEqOperationOnLeft(asOfEqOperations);
        }
        Operation result = null;
        for (int i = 0; i < operands.size(); i++)
        {
            Operation operation = (Operation) operands.get(i);
            Operation insertedOperation = operation.insertAsOfEqOperation(asOfEqOperations, insertPosition, stack);
            if (insertedOperation != null)
            {
                result = insertedOperation;
                for (int j = 0; j < operands.size(); j++)
                {
                    if (j != i)
                    {
                        Operation operation1 = (Operation) operands.get(j);
                        result = result.and(operation1);
                    }
                }
                return result;
            }
        }
        return result;
    }

    public Operation zInsertTransitiveOps(MapperStack insertPosition, InternalList toInsert, TransitivePropagator transitivePropagator)
    {
        Operation op = transitivePropagator.constructAnd(insertPosition, this, toInsert);
        if (op != this)
        {
            return op;
        }
        boolean returnNew = false;
        InternalList newOperations = new InternalList(this.operands.size());
        for (int i = 0; i < this.operands.size(); i++)
        {
            op = (Operation) operands.get(i);
            Operation inserted = op.zInsertTransitiveOps(insertPosition, toInsert, transitivePropagator);
            newOperations.add(inserted);
            if (inserted != op) returnNew = true;
        }
        return returnNew ? new AndOperation(newOperations) : this;
    }

    public Operation zInsertAsOfEqOperationOnLeft(AtomicOperation[] asOfEqOperations)
    {
        boolean haveMultiEq = false;
        InternalList newOperations = new InternalList(this.operands.size() + 1);
        for (int i = 0; i < this.operands.size(); i++)
        {
            Operation op = (Operation) operands.get(i);
            Operation inserted = op.zInsertAsOfEqOperationOnLeft(asOfEqOperations);
            if (inserted instanceof MultiEqualityOperation)
            {
                haveMultiEq = true;
            }
            newOperations.add(inserted);
        }
        if (!haveMultiEq)
        {
            newOperations.add(MultiEqualityOperation.createEqOperation(asOfEqOperations));
        }
        return new AndOperation(newOperations);
    }

    public void registerOperation(MithraDatabaseIdentifierExtractor extractor, boolean registerEquality)
    {
        for (int i = 0; i < operands.size(); i++)
        {
            Operation op = ((Operation) operands.get(i));
            op.registerOperation(extractor, registerEquality);
        }
    }

    public boolean zHasAsOfOperation()
    {
        this.combineOperands();
        for (int i = 0; i < operands.size(); i++)
        {
            if (((Operation) operands.get(i)).zHasAsOfOperation())
            {
                return true;
            }
        }
        return false;
    }

    public Operation zFlipToOneMapper(Mapper mapper)
    {
        this.combineOperands();
        int flipIndex = 0;
        Operation flipped = null;
        for (; flipIndex < operands.size(); flipIndex++)
        {
            Operation op = ((Operation) operands.get(flipIndex));
            flipped = op.zFlipToOneMapper(mapper);
            if (flipped != null) break;
        }
        if (flipped != null)
        {
            InternalList leftOver = new InternalList(this.operands.size());
            for (int i = 0; i < operands.size(); i++)
            {
                if (i != flipIndex)
                {
                    leftOver.add(operands.get(i));
                }
            }
            Operation rest = new AndOperation(leftOver);
            return flipped.and(new MappedOperation(mapper, rest));
        }
        return null;
    }

    public Operation zFindEquality(TimestampAttribute attr)
    {
        Operation result = null;
        for (int i = 0; i < operands.size() && result == null; i++)
        {
            Operation op = ((Operation) operands.get(i));
            result = op.zFindEquality(attr);
        }
        return result;
    }

    /**
     * the xor operation is used to ensure the order of operands does not impact the hashcode
     */
    public int hashCode()
    {
        this.combineOperands();
        return operands.hashCode();
    }

    public boolean equals(Object obj)
    {
        if (this == obj) return true;
        if (obj instanceof AndOperation)
        {
            AndOperation other = (AndOperation) obj;
            this.combineOperands();
            other.combineOperands();
            return this.operands.equals(other.operands);
        }
        return false;
    }

    public void addDependentPortalsToSet(Set set)
    {
        for (int i = 0; i < operands.size(); i++)
        {
            Operation op = ((Operation) operands.get(i));
            op.addDependentPortalsToSet(set);
        }
    }

    public void addDepenedentAttributesToSet(Set set)
    {
        for (int i = 0; i < operands.size(); i++)
        {
            Operation op = ((Operation) operands.get(i));
            op.addDepenedentAttributesToSet(set);
        }
    }

    public boolean isJoinedWith(MithraObjectPortal portal)
    {
        for (int i = 0; i < operands.size(); i++)
        {
            Operation op = ((Operation) operands.get(i));
            if (op.isJoinedWith(portal)) return true;
        }
        return false;
    }

    /*
    returns the combined and operation. Many operations must be combined to correctly resolve a query.
    Many operations are more efficient when combined.
    This method is internal to Mithra's operation processing.
    */
    public Operation zCombinedAnd(Operation op)
    {
        return this.and(op);
    }

    public Operation zCombinedAndWithAtomicEquality(AtomicEqualityOperation op)
    {
        return this.and(op);
    }

    public Operation zCombinedAndWithMapped(MappedOperation op)
    {
        return this.and(op);
    }

    public Operation zCombinedAndWithMultiEquality(MultiEqualityOperation op)
    {
        return this.and(op);
    }

    public Operation zCombinedAndWithAtomicGreaterThan(GreaterThanOperation op)
    {
        return this.and(op);
    }

    public Operation zCombinedAndWithAtomicGreaterThanEquals(GreaterThanEqualsOperation op)
    {
        return this.and(op);
    }

    public Operation zCombinedAndWithAtomicLessThan(LessThanOperation op)
    {
        return this.and(op);
    }

    public Operation zCombinedAndWithAtomicLessThanEquals(LessThanEqualsOperation op)
    {
        return this.and(op);
    }

    public Operation zCombinedAndWithIn(InOperation op)
    {
        return this.and(op);
    }

    public Boolean matches(Object o)
    {
        for (int i = 0; i < operands.size(); i++)
        {
            Operation op = ((Operation) operands.get(i));
            Boolean matched = op.matches(o);
            if (matched == null) return null;
            if (!matched) return false;
        }
        return true;
    }

    public boolean zPrefersBulkMatching()
    {
        this.combineOperands();
        for (int i = 0; i < this.operands.size(); i++)
        {
            if (((Operation)this.operands.get(i)).zPrefersBulkMatching()) return true;
        }
        return false;
    }

    @Override
    public String toString()
    {
        return ToStringContext.createAndToString(this);
    }

    public boolean zContainsMappedOperation()
    {
        for (int i = 0; i < this.operands.size(); i++)
        {
            if (((Operation) this.operands.get(i)).zContainsMappedOperation())
            {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean zHasParallelApply()
    {
        return true;
    }
}
