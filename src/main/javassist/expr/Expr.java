/*
 * Javassist, a Java-bytecode translator toolkit.
 * Copyright (C) 1999-2003 Shigeru Chiba. All Rights Reserved.
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License.  Alternatively, the contents of this file may be used under
 * the terms of the GNU Lesser General Public License Version 2.1 or later.
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 */

package javassist.expr;

import javassist.*;
import javassist.bytecode.*;
import javassist.compiler.*;
import java.util.LinkedList;
import java.util.Iterator;

/**
 * Expression.
 */
public abstract class Expr implements Opcode {
    int currentPos;
    CodeIterator iterator;
    CtClass thisClass;
    MethodInfo thisMethod;

    boolean edited;
    int maxLocals, maxStack;

    static final String javaLangObject = "java.lang.Object";

    /**
     * Undocumented constructor.  Do not use; internal-use only.
     */
    protected Expr(int pos, CodeIterator i, CtClass declaring, MethodInfo m) {
        currentPos = pos;
        iterator = i;
        thisClass = declaring;
        thisMethod = m;
    }

    final ConstPool getConstPool() {
        return thisMethod.getConstPool();
    }

    final boolean edited() { return edited; }

    final int locals() { return maxLocals; }

    final int stack() { return maxStack; }

    /**
     * Returns true if this method is static.
     */
    final boolean withinStatic() {
        return (thisMethod.getAccessFlags() & AccessFlag.STATIC) != 0;
    }

    /**
     * Returns the constructor or method containing the expression.
     */
    public CtBehavior where() {
        MethodInfo mi = thisMethod;
        CtBehavior[] cb = thisClass.getDeclaredBehaviors();
        for (int i = cb.length - 1; i >= 0; --i)
            if (cb[i].getMethodInfo() == mi)
                return cb[i];

        throw new RuntimeException("fatal: not found");
    }

    /**
     * Returns the list of exceptions that the expression may throw.
     * This list includes both the exceptions that the try-catch statements
     * including the expression can catch and the exceptions that
     * the throws declaration allows the method to throw.
     */
    public CtClass[] mayThrow() {
        ClassPool pool = thisClass.getClassPool();
        ConstPool cp = thisMethod.getConstPool();
        LinkedList list = new LinkedList();
        try {
            CodeAttribute ca = thisMethod.getCodeAttribute();
            ExceptionTable et = ca.getExceptionTable();
            int pos = currentPos;
            int n = et.size();
            for (int i = 0; i < n; ++i)
                if (et.startPc(i) <= pos && pos < et.endPc(i)) {
                    int t = et.catchType(i);
                    if (t > 0)
                        try {
                            addClass(list, pool.get(cp.getClassInfo(t)));
                        }
                        catch (NotFoundException e) {}
                }
        }
        catch (NullPointerException e) {}

        ExceptionsAttribute ea = thisMethod.getExceptionsAttribute();
        if (ea != null) {
            String[] exceptions = ea.getExceptions();
            if (exceptions != null) {
                int n = exceptions.length;
                for (int i = 0; i < n; ++i)
                    try {
                        addClass(list, pool.get(exceptions[i]));
                    }
                    catch (NotFoundException e) {}
            }
        }

        return (CtClass[])list.toArray(new CtClass[list.size()]);
    }

    private static void addClass(LinkedList list, CtClass c) {
        Iterator it = list.iterator();
        while (it.hasNext())
            if (it.next() == c)
                return;

        list.add(c);
    }

    /**
     * Returns the index of the bytecode corresponding to the
     * expression.
     * It is the index into the byte array containing the Java bytecode
     * that implements the method.
     */
    public int indexOfBytecode() {
        return currentPos;
    }

    /**
     * Returns the line number of the source line containing the
     * expression.
     *
     * @return -1       if this information is not available.
     */
    public int getLineNumber() {
        return thisMethod.getLineNumber(currentPos);
    }

    /**
     * Returns the source file containing the expression.
     *
     * @return null     if this information is not available.
     */
    public String getFileName() {
        ClassFile cf = thisClass.getClassFile2();
        if (cf == null)
            return null;
        else
            return cf.getSourceFile();
    }

    static final boolean checkResultValue(CtClass retType, String prog)
        throws CannotCompileException
    {
        /* Is $_ included in the source code?
         */
        boolean hasIt = (prog.indexOf(Javac.resultVarName) >= 0);
        if (!hasIt && retType != CtClass.voidType)
            throw new CannotCompileException(
                        "the resulting value is not stored in "
                        + Javac.resultVarName);

        return hasIt;
    }

    /* If isStaticCall is true, null is assigned to $0.  So $0 must
     * be declared by calling Javac.recordParams().
     *
     * After executing this method, the current stack depth might
     * be less than 0.
     */
    static final void storeStack(CtClass[] params, boolean isStaticCall,
                                 int regno, Bytecode bytecode) {
        storeStack0(0, params.length, params, regno + 1, bytecode);
        if (isStaticCall)
            bytecode.addOpcode(ACONST_NULL);

        bytecode.addAstore(regno);
    }

    private static void storeStack0(int i, int n, CtClass[] params,
                                    int regno, Bytecode bytecode) {
        if (i >= n)
            return;
        else {
            CtClass c = params[i];
            int size;
            if (c instanceof CtPrimitiveType)
                size = ((CtPrimitiveType)c).getDataSize();
            else
                size = 1;

            storeStack0(i + 1, n, params, regno + size, bytecode);
            bytecode.addStore(regno, c);
        }
    }

    protected void replace0(int pos, Bytecode bytecode, int size)
        throws BadBytecode
    {
        byte[] code = bytecode.get();
        edited = true;
        int gap = code.length - size;
        for (int i = 0; i < size; ++i)
            iterator.writeByte(NOP, pos + i);

        if (gap > 0)
            iterator.insertGap(pos, gap);

        iterator.write(code, pos);
        iterator.insert(bytecode.getExceptionTable(), pos);
        maxLocals = bytecode.getMaxLocals();
        maxStack = bytecode.getMaxStack();
    }
}
