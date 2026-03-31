/*
 * Copyright 2003,2004 The Apache Software Foundation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.sf.cglib.proxy;

import java.lang.reflect.Method;
import java.util.*;
import net.sf.cglib.core.*;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;

/**
 * 方法拦截器生成器，是CGLIB代理机制的核心组件。
 * 
 * <p>该类实现了{@link CallbackGenerator}接口，负责生成代理类中被拦截方法的字节码。
 * 当使用{@link MethodInterceptor}作为回调类型时，此类会为每个被拦截的方法生成相应的字节码。</p>
 * 
 * <h3>核心功能：</h3>
 * <ul>
 *   <li>为每个被拦截的方法生成Method字段（存储原始方法的反射对象）</li>
 *   <li>为每个被拦截的方法生成MethodProxy字段（存储方法代理对象，用于调用原始方法）</li>
 *   <li>生成"access method"（CGLIB$ACCESS_xxx）- 直接调用父类方法的桥接方法</li>
 *   <li>生成"around method" - 通过拦截器调用的代理方法</li>
 *   <li>生成静态初始化代码，初始化Method和MethodProxy字段</li>
 *   <li>生成CGLIB$findMethodProxy方法，用于根据方法签名查找对应的MethodProxy</li>
 * </ul>
 * 
 * <h3>生成代码示例：</h3>
 * <pre>
 * // 生成的字段
 * private static final Method methodName$Method;
 * private static final MethodProxy methodName$Proxy;
 * 
 * // 生成的access method（直接调用父类）
 * final ReturnType CGLIB$ACCESS$methodName(...) {
 *     super.methodName(...);
 * }
 * 
 * // 生成的代理方法（通过拦截器调用）
 * public final ReturnType methodName(...) {
 *     MethodInterceptor interceptor = (MethodInterceptor)CGLIB$CALLBACK_0;
 *     if (interceptor == null) {
 *         super.methodName(...);
 *     } else {
 *         interceptor.intercept(this, methodName$Method, args, methodName$Proxy);
 *     }
 * }
 * </pre>
 * 
 * @see MethodInterceptor
 * @see MethodProxy
 * @see CallbackGenerator
 */
class MethodInterceptorGenerator
implements CallbackGenerator
{
    /**
     * 单例实例，全局唯一的方法拦截器生成器。
     */
    public static final MethodInterceptorGenerator INSTANCE = new MethodInterceptorGenerator();

    /**
     * 空参数数组的字段名，用于优化无参方法的调用，避免重复创建空数组。
     */
    static final String EMPTY_ARGS_NAME = "CGLIB$emptyArgs";
    
    /**
     * 查找方法代理的静态方法名。
     */
    static final String FIND_PROXY_NAME = "CGLIB$findMethodProxy";
    
    /**
     * findMethodProxy方法的参数类型。
     */
    static final Class[] FIND_PROXY_TYPES = { Signature.class };

    /**
     * AbstractMethodError类型，用于抽象方法调用时抛出异常。
     */
    private static final Type ABSTRACT_METHOD_ERROR =
      TypeUtils.parseType("AbstractMethodError");
    
    /**
     * java.lang.reflect.Method类型。
     */
    private static final Type METHOD =
      TypeUtils.parseType("java.lang.reflect.Method");
    
    /**
     * ReflectUtils工具类类型。
     */
    private static final Type REFLECT_UTILS =
      TypeUtils.parseType("net.sf.cglib.core.ReflectUtils");
    
    /**
     * MethodProxy类型。
     */
    private static final Type METHOD_PROXY =
      TypeUtils.parseType("net.sf.cglib.proxy.MethodProxy");
    
    /**
     * MethodInterceptor接口类型。
     */
    private static final Type METHOD_INTERCEPTOR =
      TypeUtils.parseType("net.sf.cglib.proxy.MethodInterceptor");
    
    /**
     * Class.getDeclaredMethods()方法签名。
     */
    private static final Signature GET_DECLARED_METHODS =
      TypeUtils.parseSignature("java.lang.reflect.Method[] getDeclaredMethods()");
    
    /**
     * Method.getDeclaringClass()方法签名。
     */
    private static final Signature GET_DECLARING_CLASS =
      TypeUtils.parseSignature("Class getDeclaringClass()");
    
    /**
     * ReflectUtils.findMethods()方法签名。
     */
    private static final Signature FIND_METHODS =
      TypeUtils.parseSignature("java.lang.reflect.Method[] findMethods(String[], java.lang.reflect.Method[])");
    
    /**
     * MethodProxy.create()方法签名，用于创建MethodProxy实例。
     * 参数顺序：Class c1, Class c2, String sig, String name1, String name2
     */
    private static final Signature MAKE_PROXY =
      new Signature("create", METHOD_PROXY, new Type[]{
          Constants.TYPE_CLASS,
          Constants.TYPE_CLASS,
          Constants.TYPE_STRING,
          Constants.TYPE_STRING,
          Constants.TYPE_STRING
      });
    
    /**
     * MethodInterceptor.intercept()方法签名。
     * 参数：Object obj, Method method, Object[] args, MethodProxy proxy
     */
    private static final Signature INTERCEPT =
      new Signature("intercept", Constants.TYPE_OBJECT, new Type[]{
          Constants.TYPE_OBJECT,
          METHOD,
          Constants.TYPE_OBJECT_ARRAY,
          METHOD_PROXY
      });
    
    /**
     * CGLIB$findMethodProxy方法签名。
     */
    private static final Signature FIND_PROXY =
      new Signature(FIND_PROXY_NAME, METHOD_PROXY, new Type[]{ Constants.TYPE_SIGNATURE });
    
    /**
     * Object.toString()方法签名。
     */
    private static final Signature TO_STRING =
      TypeUtils.parseSignature("String toString()");
    
    /**
     * 转换器，将MethodInfo转换为其所属的ClassInfo。
     * 用于按类对方法进行分组。
     */
    private static final Transformer METHOD_TO_CLASS = new Transformer(){
        public Object transform(Object value) {
            return ((MethodInfo)value).getClassInfo();
        }
    };
    
    /**
     * Signature构造器签名。
     */
    private static final Signature CSTRUCT_SIGNATURE =
        TypeUtils.parseConstructor("String, String");

    /**
     * 获取存储Method对象的字段名。
     * 
     * @param impl 方法实现签名
     * @return 字段名，格式为"methodName$Method"
     */
    private String getMethodField(Signature impl) {
        return impl.getName() + "$Method";
    }
    
    /**
     * 获取存储MethodProxy对象的字段名。
     * 
     * @param impl 方法实现签名
     * @return 字段名，格式为"methodName$Proxy"
     */
    private String getMethodProxyField(Signature impl) {
        return impl.getName() + "$Proxy";
    }

    /**
     * 生成代理类中的方法拦截相关代码。
     * 
     * <p>此方法是{@link CallbackGenerator}接口的核心实现，负责为每个被拦截的方法生成：</p>
     * <ol>
     *   <li>静态字段：Method对象字段和MethodProxy对象字段</li>
     *   <li>Access Method（CGLIB$ACCESS_xxx）：直接调用父类方法的桥接方法，
     *       用于MethodProxy.invokeSuper()调用</li>
     *   <li>Around Method：代理方法，通过MethodInterceptor.intercept()进行拦截调用</li>
     * </ol>
     * 
     * <p>生成的代理方法逻辑：</p>
     * <pre>
     * public ReturnType methodName(args) {
     *     MethodInterceptor interceptor = callback;
     *     if (interceptor == null) {
     *         super.methodName(args);  // 无拦截器时直接调用父类
     *     } else {
     *         return interceptor.intercept(this, method, args, proxy);
     *     }
     * }
     * </pre>
     * 
     * @param ce 类发射器，用于生成类字节码
     * @param context 回调生成器上下文，提供方法签名转换和回调索引等功能
     * @param methods 需要拦截的方法列表
     */
    public void generate(ClassEmitter ce, Context context, List methods) {
        Map sigMap = new HashMap();
        for (Iterator it = methods.iterator(); it.hasNext();) {
            MethodInfo method = (MethodInfo)it.next();
            Signature sig = method.getSignature();
            Signature impl = context.getImplSignature(method);

            // 获取Method字段名和MethodProxy字段名
            String methodField = getMethodField(impl);
            String methodProxyField = getMethodProxyField(impl);

            // 建立方法签名到MethodProxy字段名的映射，用于后续生成findMethodProxy方法
            sigMap.put(sig.toString(), methodProxyField);
            
            // 声明静态字段：存储Method对象、MethodProxy对象和空参数数组
            ce.declare_field(Constants.PRIVATE_FINAL_STATIC, methodField, METHOD, null);
            ce.declare_field(Constants.PRIVATE_FINAL_STATIC, methodProxyField, METHOD_PROXY, null);
            ce.declare_field(Constants.PRIVATE_FINAL_STATIC, EMPTY_ARGS_NAME, Constants.TYPE_OBJECT_ARRAY, null);
            
            CodeEmitter e;

            // 生成"access method"（CGLIB$ACCESS$methodName）
            // 这是一个直接调用父类方法的桥接方法，用于MethodProxy.invokeSuper()
            e = ce.begin_method(Constants.ACC_FINAL,
                                impl,
                                method.getExceptionTypes());
            superHelper(e, method, context);
            e.return_value();
            e.end_method();

            // 生成代理方法（通过拦截器调用）
            e = context.beginMethod(ce, method);
            
            // 创建拦截器为null时的跳转标签
            Label nullInterceptor = e.make_label();
            
            // 加载回调对象（MethodInterceptor）到栈顶
            context.emitCallback(e, context.getIndex(method));
            e.dup();  // 复制一份用于null检查
            e.ifnull(nullInterceptor);  // 如果为null，跳转到nullInterceptor标签

            // 准备intercept方法的参数
            e.load_this();  // 参数1: this对象
            e.getfield(methodField);  // 参数2: Method对象
            
            // 参数3: 方法参数数组
            if (sig.getArgumentTypes().length == 0) {
                e.getfield(EMPTY_ARGS_NAME);  // 无参方法使用预定义的空数组
            } else {
                e.create_arg_array();  // 有参方法创建参数数组
            }
            
            e.getfield(methodProxyField);  // 参数4: MethodProxy对象
            e.invoke_interface(METHOD_INTERCEPTOR, INTERCEPT);  // 调用intercept方法
            e.unbox_or_zero(sig.getReturnType());  // 解包返回值
            e.return_value();  // 返回结果

            // 拦截器为null的情况：直接调用父类方法
            e.mark(nullInterceptor);
            superHelper(e, method, context);
            e.return_value();
            e.end_method();
        }
        generateFindProxy(ce, sigMap);
    }

    /**
     * 辅助方法，生成调用父类方法的字节码。
     * 
     * <p>当拦截器为null或需要调用原始方法时使用此方法生成调用父类的代码。
     * 如果方法是抽象方法，则抛出AbstractMethodError异常。</p>
     * 
     * @param e 代码发射器
     * @param method 方法信息
     * @param context 回调生成器上下文
     */
    private static void superHelper(CodeEmitter e, MethodInfo method, Context context)
    {
        if (TypeUtils.isAbstract(method.getModifiers())) {
            e.throw_exception(ABSTRACT_METHOD_ERROR, method.toString() + " is abstract" );
        } else {
            e.load_this();
            context.emitLoadArgsAndInvoke(e, method);
        }
    }

    /**
     * 生成静态初始化块的字节码。
     * 
     * <p>此方法生成代理类的静态初始化代码，主要完成以下工作：</p>
     * <ol>
     *   <li>初始化空参数数组字段（CGLIB$emptyArgs）</li>
     *   <li>按声明类对方法进行分组处理</li>
     *   <li>为每个方法查找对应的反射Method对象并存储到静态字段</li>
     *   <li>为每个方法创建MethodProxy实例并存储到静态字段</li>
     * </ol>
     * 
     * <p>生成的静态块示例：</p>
     * <pre>
     * static {
     *     CGLIB$emptyArgs = new Object[0];
     *     Class thisClass = Class.forName("ProxyClass");
     *     Class cls = Class.forName("TargetClass");
     *     String[] sigs = {"methodName", "(Args)ReturnType", ...};
     *     Method[] methods = cls.getDeclaredMethods();
     *     methods = ReflectUtils.findMethods(sigs, methods);
     *     methodName$Method = methods[0];
     *     methodName$Proxy = MethodProxy.create(cls, thisClass, "(Args)ReturnType", "methodName", "CGLIB$ACCESS$methodName");
     * }
     * </pre>
     * 
     * @param e 代码发射器
     * @param context 回调生成器上下文
     * @param methods 需要拦截的方法列表
     * @throws Exception 生成过程中可能抛出的异常
     */
    public void generateStatic(CodeEmitter e, Context context, List methods) throws Exception {
        /* generates:
           static {
             Class thisClass = Class.forName("NameOfThisClass");
             Class cls = Class.forName("java.lang.Object");
             String[] sigs = new String[]{ "toString", "()Ljava/lang/String;", ... };
             Method[] methods = cls.getDeclaredMethods();
             methods = ReflectUtils.findMethods(sigs, methods);
             METHOD_0 = methods[0];
             CGLIB$ACCESS_0 = MethodProxy.create(cls, thisClass, "()Ljava/lang/String;", "toString", "CGLIB$ACCESS_0");
             ...
           }
        */

        // 初始化空参数数组字段：CGLIB$emptyArgs = new Object[0]
        e.push(0);
        e.newarray();
        e.putfield(EMPTY_ARGS_NAME);

        // 创建局部变量，用于存储当前代理类和声明方法的类
        Local thisclass = e.make_local();
        Local declaringclass = e.make_local();
        // 加载当前代理类的Class对象并存储到局部变量
        EmitUtils.load_class_this(e);
        e.store_local(thisclass);
        
        // 按方法声明类对方法进行分组，同一类声明的方法一起处理
        Map methodsByClass = CollectionUtils.bucket(methods, METHOD_TO_CLASS);
        for (Iterator i = methodsByClass.keySet().iterator(); i.hasNext();) {
            ClassInfo classInfo = (ClassInfo)i.next();

            List classMethods = (List)methodsByClass.get(classInfo);
            // 创建String数组，存储方法名和方法描述符（交替存储）
            // 数组格式：[methodName1, descriptor1, methodName2, descriptor2, ...]
            e.push(2 * classMethods.size());
            e.newarray(Constants.TYPE_STRING);
            for (int index = 0; index < classMethods.size(); index++) {
                MethodInfo method = (MethodInfo)classMethods.get(index);
                Signature sig = method.getSignature();
                // 存储方法名到数组的偶数索引位置
                e.dup();
                e.push(2 * index);
                e.push(sig.getName());
                e.aastore();
                // 存储方法描述符到数组的奇数索引位置
                e.dup();
                e.push(2 * index + 1);
                e.push(sig.getDescriptor());
                e.aastore();
            }
            
            // 加载声明方法的类，获取其所有声明的方法
            EmitUtils.load_class(e, classInfo.getType());
            e.dup();
            e.store_local(declaringclass);
            // 调用 Class.getDeclaredMethods() 获取所有声明的方法
            e.invoke_virtual(Constants.TYPE_CLASS, GET_DECLARED_METHODS);
            // 调用 ReflectUtils.findMethods() 根据签名数组查找匹配的Method对象
            e.invoke_static(REFLECT_UTILS, FIND_METHODS);

            // 为每个方法初始化Method字段和MethodProxy字段
            for (int index = 0; index < classMethods.size(); index++) {
                MethodInfo method = (MethodInfo)classMethods.get(index);
                Signature sig = method.getSignature();
                Signature impl = context.getImplSignature(method);
                // 从findMethods返回的数组中取出Method对象，存储到静态字段
                e.dup();
                e.push(index);
                e.array_load(METHOD);
                e.putfield(getMethodField(impl));

                // 创建MethodProxy实例并存储到静态字段
                // MethodProxy.create(declaringClass, thisClass, sigDesc, methodName, accessMethodName)
                e.load_local(declaringclass);
                e.load_local(thisclass);
                e.push(sig.getDescriptor());
                e.push(sig.getName());
                e.push(impl.getName());
                e.invoke_static(METHOD_PROXY, MAKE_PROXY);
                e.putfield(getMethodProxyField(impl));
            }
            // 弹出findMethods返回的数组引用（已处理完毕）
            e.pop();
        }
    }

    /**
     * 生成CGLIB$findMethodProxy静态方法。
     * 
     * <p>此方法生成一个静态查找方法，用于根据方法签名快速查找对应的MethodProxy对象。
     * 该方法使用哈希switch语句实现高效的查找。</p>
     * 
     * <p>生成的方法示例：</p>
     * <pre>
     * public static MethodProxy CGLIB$findMethodProxy(Signature sig) {
     *     String key = sig.toString();
     *     switch(key.hashCode()) {
     *         case hash1: if (key.equals("methodName(args)returnType")) return methodName$Proxy;
     *         case hash2: if (key.equals("otherMethod(args)returnType")) return otherMethod$Proxy;
     *         ...
     *     }
     *     return null;
     * }
     * </pre>
     * 
     * <p>此方法主要用于MethodProxy的内部实现，支持在运行时根据方法签名动态查找代理对象。</p>
     * 
     * @param ce 类发射器
     * @param sigMap 方法签名到MethodProxy字段名的映射
     */
    public void generateFindProxy(ClassEmitter ce, final Map sigMap) {
        final CodeEmitter e = ce.begin_method(Constants.ACC_PUBLIC | Constants.ACC_STATIC,
                                              FIND_PROXY,
                                              null);
        e.load_arg(0);
        e.invoke_virtual(Constants.TYPE_OBJECT, TO_STRING);
        ObjectSwitchCallback callback = new ObjectSwitchCallback() {
            public void processCase(Object key, Label end) {
                e.getfield((String)sigMap.get(key));
                e.return_value();
            }
            public void processDefault() {
                e.aconst_null();
                e.return_value();
            }
        };
        EmitUtils.string_switch(e,
                                (String[])sigMap.keySet().toArray(new String[0]),
                                Constants.SWITCH_STYLE_HASH,
                                callback);
        e.end_method();
    }
}
