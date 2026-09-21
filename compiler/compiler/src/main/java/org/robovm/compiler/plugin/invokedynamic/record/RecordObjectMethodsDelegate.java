/*
 * Copyright (C) 2026 RoboVM AB
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/gpl-2.0.html>.
 */
package org.robovm.compiler.plugin.invokedynamic.record;

import org.robovm.compiler.ModuleBuilder;
import org.robovm.compiler.clazz.Clazz;
import org.robovm.compiler.config.Config;
import org.robovm.compiler.plugin.invokedynamic.InvokeDynamicCompilerPlugin;
import soot.Body;
import soot.BooleanType;
import soot.ByteType;
import soot.CharType;
import soot.DoubleType;
import soot.FloatType;
import soot.IntType;
import soot.Local;
import soot.LongType;
import soot.RefType;
import soot.Scene;
import soot.ShortType;
import soot.SootClass;
import soot.SootField;
import soot.SootMethod;
import soot.SootMethodRef;
import soot.SootResolver;
import soot.Type;
import soot.Unit;
import soot.Value;
import soot.VoidType;
import soot.jimple.AssignStmt;
import soot.jimple.ClassConstant;
import soot.jimple.DefinitionStmt;
import soot.jimple.DynamicInvokeExpr;
import soot.jimple.IntConstant;
import soot.jimple.Jimple;
import soot.jimple.NopStmt;
import soot.jimple.StringConstant;
import soot.tagkit.LineNumberTag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Desugars the {@code invokedynamic} call sites javac emits for the implicitly declared
 * {@code toString()}, {@code hashCode()} and {@code equals(Object)} methods of records
 * (bootstrapped by {@code java.lang.runtime.ObjectMethods.bootstrap}) into plain Jimple that
 * reads the component fields directly.
 *
 * The generated code follows the OpenJDK implementation of {@code ObjectMethods}:
 * <ul>
 *     <li>{@code toString}: {@code Name[a=1, b=x]}</li>
 *     <li>{@code hashCode}: {@code h = 31 * h + hash(component)} with the wrapper class
 *     {@code hashCode(primitive)} functions and {@code Objects.hashCode} for references</li>
 *     <li>{@code equals}: identity, {@code instanceof} check and component wise comparison using
 *     {@code ==} for integral types, {@code Float.compare}/{@code Double.compare} for floating point
 *     types and {@code Objects.equals} for references</li>
 * </ul>
 * The bootstrap arguments are expected to be {@code (Class recordClass, String "a;b;c" [, getters...])};
 * the getter method handles are ignored as they are stripped by
 * {@link org.robovm.compiler.clazz.RewritingClassProvider} (Soot 2.5 can't represent field handles).
 */
public class RecordObjectMethodsDelegate implements InvokeDynamicCompilerPlugin.Delegate {
    private static final String BOOTSTRAP_CLASS = "java.lang.runtime.ObjectMethods";
    private static final String BOOTSTRAP_METHOD = "bootstrap";

    private int tmpCounter = 0;
    private boolean initialized = false;

    private RefType stringBuilderType;
    private SootMethodRef sbInit;
    private SootMethodRef sbToString;
    private SootMethodRef sbAppendString;
    private SootMethodRef sbAppendObject;
    private SootMethodRef sbAppendInt;
    private SootMethodRef sbAppendLong;
    private SootMethodRef sbAppendFloat;
    private SootMethodRef sbAppendDouble;
    private SootMethodRef sbAppendBoolean;
    private SootMethodRef sbAppendChar;
    private SootMethodRef objectsEquals;
    private SootMethodRef objectsHashCode;
    private SootMethodRef floatCompare;
    private SootMethodRef doubleCompare;
    private SootMethodRef booleanHashCode;
    private SootMethodRef longHashCode;
    private SootMethodRef floatHashCode;
    private SootMethodRef doubleHashCode;

    public static boolean isRecordBootstrapMethod(SootMethodRef methodRef) {
        return methodRef.declaringClass().getName().equals(BOOTSTRAP_CLASS)
                && methodRef.name().equals(BOOTSTRAP_METHOD);
    }

    /**
     * Lazy initialization, see the note in {@code InvokeDynamicCompilerPlugin.UnrecognizedBootstrapDelegate}:
     * Soot globals are reset after the plugins are created.
     */
    private void initializeIfRequired() {
        if (initialized)
            return;

        SootResolver r = SootResolver.v();
        Scene scene = Scene.v();
        SootClass stringBuilder = r.makeClassRef("java.lang.StringBuilder");
        SootClass string = r.makeClassRef("java.lang.String");
        SootClass object = r.makeClassRef("java.lang.Object");
        SootClass objects = r.makeClassRef("java.util.Objects");
        SootClass booleanClass = r.makeClassRef("java.lang.Boolean");
        SootClass longClass = r.makeClassRef("java.lang.Long");
        SootClass floatClass = r.makeClassRef("java.lang.Float");
        SootClass doubleClass = r.makeClassRef("java.lang.Double");

        stringBuilderType = stringBuilder.getType();
        RefType stringType = string.getType();
        RefType objectType = object.getType();
        sbInit = scene.makeMethodRef(stringBuilder, "<init>", Collections.<Type>emptyList(), VoidType.v(), false);
        sbToString = scene.makeMethodRef(stringBuilder, "toString", Collections.<Type>emptyList(), stringType, false);
        sbAppendString = appendRef(scene, stringBuilder, stringType);
        sbAppendObject = appendRef(scene, stringBuilder, objectType);
        sbAppendInt = appendRef(scene, stringBuilder, IntType.v());
        sbAppendLong = appendRef(scene, stringBuilder, LongType.v());
        sbAppendFloat = appendRef(scene, stringBuilder, FloatType.v());
        sbAppendDouble = appendRef(scene, stringBuilder, DoubleType.v());
        sbAppendBoolean = appendRef(scene, stringBuilder, BooleanType.v());
        sbAppendChar = appendRef(scene, stringBuilder, CharType.v());
        objectsEquals = scene.makeMethodRef(objects, "equals", Arrays.<Type>asList(objectType, objectType), BooleanType.v(), true);
        objectsHashCode = scene.makeMethodRef(objects, "hashCode", Collections.<Type>singletonList(objectType), IntType.v(), true);
        floatCompare = scene.makeMethodRef(floatClass, "compare", Arrays.<Type>asList(FloatType.v(), FloatType.v()), IntType.v(), true);
        doubleCompare = scene.makeMethodRef(doubleClass, "compare", Arrays.<Type>asList(DoubleType.v(), DoubleType.v()), IntType.v(), true);
        booleanHashCode = scene.makeMethodRef(booleanClass, "hashCode", Collections.<Type>singletonList(BooleanType.v()), IntType.v(), true);
        longHashCode = scene.makeMethodRef(longClass, "hashCode", Collections.<Type>singletonList(LongType.v()), IntType.v(), true);
        floatHashCode = scene.makeMethodRef(floatClass, "hashCode", Collections.<Type>singletonList(FloatType.v()), IntType.v(), true);
        doubleHashCode = scene.makeMethodRef(doubleClass, "hashCode", Collections.<Type>singletonList(DoubleType.v()), IntType.v(), true);
        initialized = true;
    }

    private static SootMethodRef appendRef(Scene scene, SootClass stringBuilder, Type paramType) {
        return scene.makeMethodRef(stringBuilder, "append", Collections.singletonList(paramType), stringBuilder.getType(), false);
    }

    @Override
    public void beforeMethod(Config config, Clazz clazz, SootMethod method, ModuleBuilder moduleBuilder) {
        tmpCounter = 0;
    }

    @Override
    public LinkedList<Unit> transformDynamicInvoke(Config config, Clazz clazz, SootClass sootClass, SootMethod method,
                                                   DefinitionStmt defStmt, DynamicInvokeExpr invokeExpr,
                                                   ModuleBuilder moduleBuilder) {
        if (!isRecordBootstrapMethod(invokeExpr.getBootstrapMethodRef())) {
            return null;
        }
        initializeIfRequired();

        String methodName = invokeExpr.getMethodRef().name();
        List<Value> bsmArgs = invokeExpr.getBootstrapArgs();
        if (bsmArgs.size() < 2 || !(bsmArgs.get(0) instanceof ClassConstant) || !(bsmArgs.get(1) instanceof StringConstant)) {
            throw new IllegalArgumentException("Unexpected bootstrap arguments of " + BOOTSTRAP_CLASS + "." + BOOTSTRAP_METHOD
                    + " in " + method.getSignature() + ": " + bsmArgs);
        }
        String recordClassName = ((ClassConstant) bsmArgs.get(0)).getValue().replace('/', '.');
        SootClass recordClass = recordClassName.equals(sootClass.getName())
                ? sootClass
                : SootResolver.v().resolveClass(recordClassName, SootClass.SIGNATURES);
        String names = ((StringConstant) bsmArgs.get(1)).value;
        List<SootField> components = new ArrayList<>();
        if (!names.isEmpty()) {
            for (String name : names.split(";")) {
                if (!recordClass.declaresFieldByName(name)) {
                    throw new IllegalArgumentException("Record component field '" + name + "' not found in " + recordClassName);
                }
                components.add(recordClass.getFieldByName(name));
            }
        }

        Body body = method.retrieveActiveBody();
        Local lhs = (Local) defStmt.getLeftOp();
        List<Value> args = invokeExpr.getArgs();
        LinkedList<Unit> newUnits;
        switch (methodName) {
            case "toString":
                newUnits = generateToString(body, lhs, asLocal(args.get(0)), recordClass, components);
                break;
            case "hashCode":
                newUnits = generateHashCode(body, lhs, asLocal(args.get(0)), components);
                break;
            case "equals":
                newUnits = generateEquals(body, lhs, asLocal(args.get(0)), asLocal(args.get(1)), recordClass, components);
                break;
            default:
                throw new IllegalArgumentException("Unsupported " + BOOTSTRAP_CLASS + " method '" + methodName
                        + "' in " + method.getSignature());
        }

        // attach the line number of the replaced invokedynamic to all new units to keep debugger information intact
        for (Object o : defStmt.getTags()) {
            if (o instanceof LineNumberTag) {
                LineNumberTag ln = (LineNumberTag) o;
                for (Unit u : newUnits)
                    u.addTag(new LineNumberTag(ln.getLineNumber()));
                break;
            }
        }
        return newUnits;
    }

    private static Local asLocal(Value v) {
        if (!(v instanceof Local)) {
            throw new IllegalArgumentException("Expected local as invokedynamic argument but got " + v);
        }
        return (Local) v;
    }

    private Local newLocal(Body body, String name, Type type) {
        Local l = Jimple.v().newLocal("$rec_" + name + (tmpCounter++), type);
        body.getLocals().add(l);
        return l;
    }

    private Local readComponent(Body body, LinkedList<Unit> units, Local base, SootField field) {
        Local value = newLocal(body, field.getName(), field.getType());
        units.add(Jimple.v().newAssignStmt(value, Jimple.v().newInstanceFieldRef(base, field.makeRef())));
        return value;
    }

    private static boolean isIntLike(Type t) {
        return t instanceof IntType || t instanceof ShortType || t instanceof ByteType
                || t instanceof CharType || t instanceof BooleanType;
    }

    /**
     * Simple name of the record as {@code Class.getSimpleName()} would return it: strips the outer
     * class prefix of nested records and the numeric prefix of local records.
     */
    public static String simpleName(SootClass recordClass) {
        String name = recordClass.getShortName();
        int idx = name.lastIndexOf('$');
        if (idx >= 0) {
            name = name.substring(idx + 1);
            int start = 0;
            while (start < name.length() && Character.isDigit(name.charAt(start))) {
                start++;
            }
            name = name.substring(start);
        }
        return name;
    }

    private SootMethodRef appendRefFor(Type t) {
        if (t instanceof BooleanType) return sbAppendBoolean;
        if (t instanceof CharType) return sbAppendChar;
        if (isIntLike(t)) return sbAppendInt;
        if (t instanceof LongType) return sbAppendLong;
        if (t instanceof FloatType) return sbAppendFloat;
        if (t instanceof DoubleType) return sbAppendDouble;
        if (t instanceof RefType && ((RefType) t).getClassName().equals("java.lang.String")) return sbAppendString;
        return sbAppendObject;
    }

    private LinkedList<Unit> generateToString(Body body, Local lhs, Local base, SootClass recordClass, List<SootField> components) {
        Jimple j = Jimple.v();
        LinkedList<Unit> units = new LinkedList<>();
        Local sb = newLocal(body, "sb", stringBuilderType);
        units.add(j.newAssignStmt(sb, j.newNewExpr(stringBuilderType)));
        units.add(j.newInvokeStmt(j.newSpecialInvokeExpr(sb, sbInit)));
        String name = simpleName(recordClass);
        if (components.isEmpty()) {
            units.add(j.newInvokeStmt(j.newVirtualInvokeExpr(sb, sbAppendString, StringConstant.v(name + "[]"))));
        } else {
            for (int i = 0; i < components.size(); i++) {
                SootField field = components.get(i);
                String prefix = (i == 0 ? name + "[" : ", ") + field.getName() + "=";
                units.add(j.newInvokeStmt(j.newVirtualInvokeExpr(sb, sbAppendString, StringConstant.v(prefix))));
                Local value = readComponent(body, units, base, field);
                units.add(j.newInvokeStmt(j.newVirtualInvokeExpr(sb, appendRefFor(field.getType()), value)));
            }
            units.add(j.newInvokeStmt(j.newVirtualInvokeExpr(sb, sbAppendString, StringConstant.v("]"))));
        }
        units.add(j.newAssignStmt(lhs, j.newVirtualInvokeExpr(sb, sbToString)));
        return units;
    }

    private LinkedList<Unit> generateHashCode(Body body, Local lhs, Local base, List<SootField> components) {
        Jimple j = Jimple.v();
        LinkedList<Unit> units = new LinkedList<>();
        Local hash = newLocal(body, "hash", IntType.v());
        units.add(j.newAssignStmt(hash, IntConstant.v(0)));
        for (SootField field : components) {
            Type t = field.getType();
            Local value = readComponent(body, units, base, field);
            Local componentHash;
            if (t instanceof BooleanType) {
                componentHash = newLocal(body, "h", IntType.v());
                units.add(j.newAssignStmt(componentHash, j.newStaticInvokeExpr(booleanHashCode, value)));
            } else if (isIntLike(t)) {
                // Integer/Short/Byte/Character.hashCode(x) == (int) x
                componentHash = value;
            } else if (t instanceof LongType) {
                componentHash = newLocal(body, "h", IntType.v());
                units.add(j.newAssignStmt(componentHash, j.newStaticInvokeExpr(longHashCode, value)));
            } else if (t instanceof FloatType) {
                componentHash = newLocal(body, "h", IntType.v());
                units.add(j.newAssignStmt(componentHash, j.newStaticInvokeExpr(floatHashCode, value)));
            } else if (t instanceof DoubleType) {
                componentHash = newLocal(body, "h", IntType.v());
                units.add(j.newAssignStmt(componentHash, j.newStaticInvokeExpr(doubleHashCode, value)));
            } else {
                componentHash = newLocal(body, "h", IntType.v());
                units.add(j.newAssignStmt(componentHash, j.newStaticInvokeExpr(objectsHashCode, value)));
            }
            units.add(j.newAssignStmt(hash, j.newMulExpr(hash, IntConstant.v(31))));
            units.add(j.newAssignStmt(hash, j.newAddExpr(hash, componentHash)));
        }
        units.add(j.newAssignStmt(lhs, hash));
        return units;
    }

    private LinkedList<Unit> generateEquals(Body body, Local lhs, Local base, Local other, SootClass recordClass,
                                            List<SootField> components) {
        Jimple j = Jimple.v();
        LinkedList<Unit> units = new LinkedList<>();
        RefType recordType = recordClass.getType();
        AssignStmt returnTrue = j.newAssignStmt(lhs, IntConstant.v(1));
        AssignStmt returnFalse = j.newAssignStmt(lhs, IntConstant.v(0));
        NopStmt end = j.newNopStmt();

        // if (this == other) return true
        units.add(j.newIfStmt(j.newEqExpr(base, other), returnTrue));
        // if (!(other instanceof Record)) return false
        Local isInstance = newLocal(body, "inst", BooleanType.v());
        units.add(j.newAssignStmt(isInstance, j.newInstanceOfExpr(other, recordType)));
        units.add(j.newIfStmt(j.newEqExpr(isInstance, IntConstant.v(0)), returnFalse));
        Local otherRecord = newLocal(body, "other", recordType);
        units.add(j.newAssignStmt(otherRecord, j.newCastExpr(other, recordType)));

        for (SootField field : components) {
            Type t = field.getType();
            Local a = readComponent(body, units, base, field);
            Local b = readComponent(body, units, otherRecord, field);
            if (isIntLike(t)) {
                units.add(j.newIfStmt(j.newNeExpr(a, b), returnFalse));
            } else if (t instanceof LongType) {
                Local cmp = newLocal(body, "cmp", IntType.v());
                units.add(j.newAssignStmt(cmp, j.newCmpExpr(a, b)));
                units.add(j.newIfStmt(j.newNeExpr(cmp, IntConstant.v(0)), returnFalse));
            } else if (t instanceof FloatType) {
                Local cmp = newLocal(body, "cmp", IntType.v());
                units.add(j.newAssignStmt(cmp, j.newStaticInvokeExpr(floatCompare, a, b)));
                units.add(j.newIfStmt(j.newNeExpr(cmp, IntConstant.v(0)), returnFalse));
            } else if (t instanceof DoubleType) {
                Local cmp = newLocal(body, "cmp", IntType.v());
                units.add(j.newAssignStmt(cmp, j.newStaticInvokeExpr(doubleCompare, a, b)));
                units.add(j.newIfStmt(j.newNeExpr(cmp, IntConstant.v(0)), returnFalse));
            } else {
                Local eq = newLocal(body, "eq", BooleanType.v());
                units.add(j.newAssignStmt(eq, j.newStaticInvokeExpr(objectsEquals, a, b)));
                units.add(j.newIfStmt(j.newEqExpr(eq, IntConstant.v(0)), returnFalse));
            }
        }
        units.add(returnTrue);
        units.add(j.newGotoStmt(end));
        units.add(returnFalse);
        units.add(end);
        return units;
    }
}
