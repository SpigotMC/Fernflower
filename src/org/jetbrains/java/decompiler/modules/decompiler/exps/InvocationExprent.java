/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.java.decompiler.modules.decompiler.exps;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.main.ClassesProcessor.ClassNode;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.TextBuffer;
import org.jetbrains.java.decompiler.main.collectors.BytecodeMappingTracer;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.rels.MethodWrapper;
import org.jetbrains.java.decompiler.modules.decompiler.ExprProcessor;
import org.jetbrains.java.decompiler.modules.decompiler.vars.CheckTypesResult;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarProcessor;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionPaar;
import org.jetbrains.java.decompiler.struct.StructClass;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jetbrains.java.decompiler.struct.consts.LinkConstant;
import org.jetbrains.java.decompiler.struct.gen.MethodDescriptor;
import org.jetbrains.java.decompiler.struct.gen.VarType;
import org.jetbrains.java.decompiler.util.InterpreterUtil;
import org.jetbrains.java.decompiler.util.ListStack;

import java.util.*;


public class InvocationExprent extends Exprent {

  public static final int INVOKE_SPECIAL = 1;
  public static final int INVOKE_VIRTUAL = 2;
  public static final int INVOKE_STATIC = 3;
  public static final int INVOKE_INTERFACE = 4;
  public static final int INVOKE_DYNAMIC = 5;

  public static final int TYP_GENERAL = 1;
  public static final int TYP_INIT = 2;
  public static final int TYP_CLINIT = 3;

  public static final int CONSTRUCTOR_NOT = 0;
  public static final int CONSTRUCTOR_THIS = 1;
  public static final int CONSTRUCTOR_SUPER = 2;

  private String name;

  private String classname;

  private boolean isStatic;

  private boolean canIgnoreBoxing = true;

  private int functype = TYP_GENERAL;

  private Exprent instance;

  private MethodDescriptor descriptor;

  private String stringDescriptor;

  private String invoke_dynamic_classsuffix;

  private int invocationTyp = INVOKE_VIRTUAL;

  private List<Exprent> lstParameters = new ArrayList<Exprent>();

  {
    this.type = EXPRENT_INVOCATION;
  }

  public InvocationExprent() {
  }

  public InvocationExprent(int opcode, LinkConstant cn, ListStack<Exprent> stack, int dynamic_invokation_type) {

    name = cn.elementname;
    classname = cn.classname;

    switch (opcode) {
      case CodeConstants.opc_invokestatic:
        invocationTyp = INVOKE_STATIC;
        break;
      case CodeConstants.opc_invokespecial:
        invocationTyp = INVOKE_SPECIAL;
        break;
      case CodeConstants.opc_invokevirtual:
        invocationTyp = INVOKE_VIRTUAL;
        break;
      case CodeConstants.opc_invokeinterface:
        invocationTyp = INVOKE_INTERFACE;
        break;
      case CodeConstants.opc_invokedynamic:
        invocationTyp = INVOKE_DYNAMIC;

        classname = "java/lang/Class"; // dummy class name
        invoke_dynamic_classsuffix = "##Lambda_" + cn.index1 + "_" + cn.index2;
    }

    if ("<init>".equals(name)) {
      functype = TYP_INIT;
    }
    else if ("<clinit>".equals(name)) {
      functype = TYP_CLINIT;
    }

    stringDescriptor = cn.descriptor;
    descriptor = MethodDescriptor.parseDescriptor(cn.descriptor);

    for (int i = 0; i < descriptor.params.length; i++) {
      lstParameters.add(0, stack.pop());
    }

    if (opcode == CodeConstants.opc_invokedynamic) {
      if (dynamic_invokation_type == CodeConstants.CONSTANT_MethodHandle_REF_invokeStatic) {
        isStatic = true;
      }
      else {
        if (!lstParameters.isEmpty())
        instance = lstParameters
          .get(0); // FIXME: remove the first parameter completely from the list. It's the object type for a virtual lambda method.
      }
    }
    else if (opcode == CodeConstants.opc_invokestatic) {
      isStatic = true;
    }
    else {
      instance = stack.pop();
    }
  }

  private InvocationExprent(InvocationExprent expr) {
    name = expr.getName();
    classname = expr.getClassname();
    isStatic = expr.isStatic();
    canIgnoreBoxing = expr.canIgnoreBoxing;
    functype = expr.getFunctype();
    instance = expr.getInstance();
    if (instance != null) {
      instance = instance.copy();
    }
    invocationTyp = expr.getInvocationTyp();
    invoke_dynamic_classsuffix = expr.getInvokeDynamicClassSuffix();
    stringDescriptor = expr.getStringDescriptor();
    descriptor = expr.getDescriptor();
    lstParameters = new ArrayList<Exprent>(expr.getLstParameters());
    for (int i = 0; i < lstParameters.size(); i++) {
      lstParameters.set(i, lstParameters.get(i).copy());
    }
  }


  public VarType getExprType() {
    return descriptor.ret;
  }

  public CheckTypesResult checkExprTypeBounds() {
    CheckTypesResult result = new CheckTypesResult();

    for (int i = 0; i < lstParameters.size(); i++) {
      Exprent parameter = lstParameters.get(i);

      VarType leftType = descriptor.params[i];

      result.addMinTypeExprent(parameter, VarType.getMinTypeInFamily(leftType.type_family));
      result.addMaxTypeExprent(parameter, leftType);
    }

    return result;
  }

  public List<Exprent> getAllExprents() {
    List<Exprent> lst = new ArrayList<Exprent>();
    if (instance != null) {
      lst.add(instance);
    }
    lst.addAll(lstParameters);
    return lst;
  }


  public Exprent copy() {
    return new InvocationExprent(this);
  }

  @Override
  public String toJava(int indent, BytecodeMappingTracer tracer) {
    StringBuilder buf = new StringBuilder("");

    String super_qualifier = null;
    boolean isInstanceThis = false;

    tracer.addMapping(bytecode);

    if (instance instanceof InvocationExprent) {
      ((InvocationExprent) instance).markUsingBoxingResult();
    }

    if (isStatic) {
      if (isBoxingCall() && canIgnoreBoxing) {
        // process general "boxing" calls, e.g. 'Object[] data = { true }' or 'Byte b = 123'
        // here 'byte' and 'short' values do not need an explicit narrowing type cast
        TextBuffer buf2 = new TextBuffer();
        ExprProcessor.getCastedExprent(lstParameters.get(0), descriptor.params[0], buf2, indent, false, false, tracer);
        return buf2.toString();
      }

      ClassNode node = (ClassNode)DecompilerContext.getProperty(DecompilerContext.CURRENT_CLASS_NODE);
      if (node == null || !classname.equals(node.classStruct.qualifiedName)) {
        buf.append(DecompilerContext.getImportCollector().getShortName(ExprProcessor.buildJavaClassName(classname)));
      }
    }
    else {

      if (instance != null && instance.type == Exprent.EXPRENT_VAR) {
        VarExprent instvar = (VarExprent)instance;
        VarVersionPaar varpaar = new VarVersionPaar(instvar);

        VarProcessor vproc = instvar.getProcessor();
        if (vproc == null) {
          MethodWrapper current_meth = (MethodWrapper)DecompilerContext.getProperty(DecompilerContext.CURRENT_METHOD_WRAPPER);
          if (current_meth != null) {
            vproc = current_meth.varproc;
          }
        }

        String this_classname = null;
        if (vproc != null) {
          this_classname = vproc.getThisvars().get(varpaar);
        }

        if (this_classname != null) {
          isInstanceThis = true;

          if (invocationTyp == INVOKE_SPECIAL) {
            if (!classname.equals(this_classname)) { // TODO: direct comparison to the super class?
              super_qualifier = this_classname;
            }
          }
        }
      }

      if (functype == TYP_GENERAL) {
        if (super_qualifier != null) {
          StructClass current_class = ((ClassNode)DecompilerContext.getProperty(DecompilerContext.CURRENT_CLASS_NODE)).classStruct;

          if (!super_qualifier.equals(current_class.qualifiedName)) {
            buf.append(DecompilerContext.getImportCollector().getShortName(ExprProcessor.buildJavaClassName(super_qualifier)));
            buf.append(".");
          }
          buf.append("super");
        }
        else {
          String res = instance.toJava(indent, tracer);

          if (isUnboxingCall()) {
            // we don't print the unboxing call - no need to bother with the instance wrapping / casting
            buf.append(res);
            return buf.toString();
          }

          VarType rightType = instance.getExprType();
          VarType leftType = new VarType(CodeConstants.TYPE_OBJECT, 0, classname);

          if (rightType.equals(VarType.VARTYPE_OBJECT) && !leftType.equals(rightType)) {
            buf.append("((").append(ExprProcessor.getCastTypeName(leftType)).append(")");

            if (instance.getPrecedence() >= FunctionExprent.getPrecedence(FunctionExprent.FUNCTION_CAST)) {
              res = "(" + res + ")";
            }
            buf.append(res).append(")");
          }
          else if (instance.getPrecedence() > getPrecedence()) {
            buf.append("(").append(res).append(")");
          }
          else {
            buf.append(res);
          }
        }
      }
    }

    switch (functype) {
      case TYP_GENERAL:
        if (VarExprent.VAR_NAMELESS_ENCLOSURE.equals(buf.toString())) {
          buf = new StringBuilder("");
        }

        if (buf.length() > 0) {
          buf.append(".");
        }

        buf.append(name);
        if (invocationTyp == INVOKE_DYNAMIC) {
          buf.append("<invokedynamic>");
        }
        buf.append("(");

        break;
      case TYP_CLINIT:
        throw new RuntimeException("Explicite invocation of <clinit>");
      case TYP_INIT:
        if (super_qualifier != null) {
          buf.append("super(");
        }
        else if (isInstanceThis) {
          buf.append("this(");
        }
        else {
          buf.append(instance.toJava(indent, tracer));
          buf.append(".<init>(");
          //				throw new RuntimeException("Unrecognized invocation of <init>"); // FIXME: activate
        }
    }

    List<VarVersionPaar> sigFields = null;
    boolean isEnum = false;
    if (functype == TYP_INIT) {
      ClassNode newNode = DecompilerContext.getClassProcessor().getMapRootClasses().get(classname);

      if (newNode != null) {  // own class
        if (newNode.wrapper != null) {
          sigFields = newNode.wrapper.getMethodWrapper("<init>", stringDescriptor).signatureFields;
        }
        else {
          if (newNode.type == ClassNode.CLASS_MEMBER && (newNode.access & CodeConstants.ACC_STATIC) == 0) { // non-static member class
            sigFields = new ArrayList<VarVersionPaar>(Collections.nCopies(lstParameters.size(), (VarVersionPaar)null));
            sigFields.set(0, new VarVersionPaar(-1, 0));
          }
        }
        isEnum = newNode.classStruct.hasModifier(CodeConstants.ACC_ENUM) && DecompilerContext.getOption(IFernflowerPreferences.DECOMPILE_ENUM);
      }
    }

    Set<Integer> setAmbiguousParameters = getAmbiguousParameters();

    // omit 'new Type[] {}' for the last parameter of a vararg method call
    if (lstParameters.size() == descriptor.params.length && isVarArgCall()) {
      Exprent lastParam = lstParameters.get(lstParameters.size() - 1);
      if (lastParam.type == EXPRENT_NEW && lastParam.getExprType().arraydim >= 1) {
        ((NewExprent) lastParam).setVarArgParam(true);
      }
    }

    boolean firstpar = true;
    int start = isEnum ? 2 : 0;
    for (int i = start; i < lstParameters.size(); i++) {
      if (sigFields == null || sigFields.get(i) == null) {
        TextBuffer buff = new TextBuffer();
        ExprProcessor.getCastedExprent(lstParameters.get(i), descriptor.params[i], buff, indent, true, setAmbiguousParameters.contains(i), tracer);


        // the last "new Object[0]" in the vararg call is not printed
        if (buff.length() > 0) {
          if (!firstpar) {
            buf.append(", ");
          }
          buf.append(buff);
        }
        firstpar = false;
      }
    }
    buf.append(")");

    return buf.toString();
  }

  private boolean isVarArgCall() {
    StructClass cl = DecompilerContext.getStructContext().getClass(classname);
    if (cl != null) {
      StructMethod mt = cl.getMethod(InterpreterUtil.makeUniqueKey(name, stringDescriptor));
      if (mt != null) {
        return mt.hasModifier(CodeConstants.ACC_VARARGS);
      }
    }
    else {
      // TODO: try to check the class on the classpath
    }
    return false;
  }

  private boolean isBoxingCall() {
    if (isStatic && "valueOf".equals(name) && lstParameters.size() == 1) {
      int paramType = lstParameters.get(0).getExprType().type;

      // special handling for ambiguous types
      if (lstParameters.get(0).type == Exprent.EXPRENT_CONST) {
        // 'Integer.valueOf(1)' has '1' type detected as TYPE_BYTECHAR
        // 'Integer.valueOf(40_000)' has '40_000' type detected as TYPE_CHAR
        // so we check the type family instead
        if (lstParameters.get(0).getExprType().type_family == CodeConstants.TYPE_FAMILY_INTEGER) {
          if (classname.equals("java/lang/Integer")) {
            return true;
          }
        }

        if (paramType == CodeConstants.TYPE_BYTECHAR || paramType == CodeConstants.TYPE_SHORTCHAR) {
          if (classname.equals("java/lang/Character")) {
            return true;
          }
        }
      }

      return classname.equals(getClassNameForPrimitiveType(paramType));
    }

    return false;
  }

  public void markUsingBoxingResult() {
    canIgnoreBoxing = false;
  }

  // TODO: move to CodeConstants ???
  private static String getClassNameForPrimitiveType(int type) {
    switch (type) {
      case CodeConstants.TYPE_BOOLEAN:
        return "java/lang/Boolean";
      case CodeConstants.TYPE_BYTE:
      case CodeConstants.TYPE_BYTECHAR:
        return "java/lang/Byte";
      case CodeConstants.TYPE_CHAR:
        return "java/lang/Character";
      case CodeConstants.TYPE_SHORT:
      case CodeConstants.TYPE_SHORTCHAR:
        return "java/lang/Short";
      case CodeConstants.TYPE_INT:
        return "java/lang/Integer";
      case CodeConstants.TYPE_LONG:
        return "java/lang/Long";
      case CodeConstants.TYPE_FLOAT:
        return "java/lang/Float";
      case CodeConstants.TYPE_DOUBLE:
        return "java/lang/Double";
    }
    return null;
  }

  private static final Map<String, String> UNBOXING_METHODS;

  static {
    UNBOXING_METHODS = new HashMap<String, String>();
    UNBOXING_METHODS.put("booleanValue", "java/lang/Boolean");
    UNBOXING_METHODS.put("byteValue", "java/lang/Byte");
    UNBOXING_METHODS.put("shortValue", "java/lang/Short");
    UNBOXING_METHODS.put("intValue", "java/lang/Integer");
    UNBOXING_METHODS.put("longValue", "java/lang/Long");
    UNBOXING_METHODS.put("floatValue", "java/lang/Float");
    UNBOXING_METHODS.put("doubleValue", "java/lang/Double");
    UNBOXING_METHODS.put("charValue", "java/lang/Character");
  }

  private boolean isUnboxingCall() {
    return !isStatic && lstParameters.size() == 0 && classname.equals(UNBOXING_METHODS.get(name));
  }

  private Set<StructClass> addAllSuper(Set<StructClass> set, String clazz) {
    StructClass cstr = DecompilerContext.getStructContext().getClass(clazz);
    if (cstr == null) {
      return set;
    }
    set.add(cstr);
    for (String inter : cstr.getInterfaceNames()) {
      addAllSuper(set, inter);
    }
    addAllSuper(set, cstr.superClass.getString());

    return set;
  }

  private Set<Integer> getAmbiguousParameters() {

    Set<Integer> ret = new HashSet<Integer>();

    List<MethodDescriptor> lstMethods = new ArrayList<MethodDescriptor>();
    for (StructClass cstr : addAllSuper(new HashSet<StructClass>(), classname)) {
      for (StructMethod meth : cstr.getMethods()) {
        if (name.equals(meth.getName())) {
          MethodDescriptor md = MethodDescriptor.parseDescriptor(meth.getDescriptor());
          if (md.params.length == descriptor.params.length) {
            boolean equals = true;
            for (int i = 0; i < md.params.length; i++) {
              if (md.params[i].type_family != descriptor.params[i].type_family) {
                equals = false;
                break;
              }
            }

            if (equals) {
              lstMethods.add(md);
            }
          }
        }
      }

      if (lstMethods.size() > 1) {
        for (int i = 0; i < descriptor.params.length; i++) {
          VarType partype = descriptor.params[i];

          for (MethodDescriptor md : lstMethods) {
            if (!partype.equals(md.params[i])) {
              ret.add(i);
              break;
            }
          }
        }
      }
    }

    return ret;
  }

  public boolean equals(Object o) {
    if (o == this) return true;
    if (o == null || !(o instanceof InvocationExprent)) return false;

    InvocationExprent it = (InvocationExprent)o;
    return InterpreterUtil.equalObjects(name, it.getName()) &&
           InterpreterUtil.equalObjects(classname, it.getClassname()) &&
           isStatic == it.isStatic() &&
           InterpreterUtil.equalObjects(instance, it.getInstance()) &&
           InterpreterUtil.equalObjects(descriptor, it.getDescriptor()) &&
           functype == it.getFunctype() &&
           InterpreterUtil.equalLists(lstParameters, it.getLstParameters());
  }

  public void replaceExprent(Exprent oldexpr, Exprent newexpr) {
    if (oldexpr == instance) {
      instance = newexpr;
    }

    for (int i = 0; i < lstParameters.size(); i++) {
      if (oldexpr == lstParameters.get(i)) {
        lstParameters.set(i, newexpr);
      }
    }
  }

  public List<Exprent> getLstParameters() {
    return lstParameters;
  }

  public void setLstParameters(List<Exprent> lstParameters) {
    this.lstParameters = lstParameters;
  }

  public MethodDescriptor getDescriptor() {
    return descriptor;
  }

  public void setDescriptor(MethodDescriptor descriptor) {
    this.descriptor = descriptor;
  }

  public String getClassname() {
    return classname;
  }

  public void setClassname(String classname) {
    this.classname = classname;
  }

  public int getFunctype() {
    return functype;
  }

  public void setFunctype(int functype) {
    this.functype = functype;
  }

  public Exprent getInstance() {
    return instance;
  }

  public void setInstance(Exprent instance) {
    this.instance = instance;
  }

  public boolean isStatic() {
    return isStatic;
  }

  public void setStatic(boolean isStatic) {
    this.isStatic = isStatic;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getStringDescriptor() {
    return stringDescriptor;
  }

  public void setStringDescriptor(String stringDescriptor) {
    this.stringDescriptor = stringDescriptor;
  }

  public int getInvocationTyp() {
    return invocationTyp;
  }

  public void setInvocationTyp(int invocationTyp) {
    this.invocationTyp = invocationTyp;
  }

  public String getInvokeDynamicClassSuffix() {
    return invoke_dynamic_classsuffix;
  }
}
