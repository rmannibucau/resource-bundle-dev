package com.github.rmannibucau.resourcebundle.dev;

import static java.util.Optional.ofNullable;
import static org.objectweb.asm.Opcodes.AASTORE;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.ACONST_NULL;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ANEWARRAY;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASM7;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.BIPUSH;
import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.F_APPEND;
import static org.objectweb.asm.Opcodes.F_SAME;
import static org.objectweb.asm.Opcodes.F_SAME1;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.H_INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.H_INVOKESTATIC;
import static org.objectweb.asm.Opcodes.H_INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.ICONST_2;
import static org.objectweb.asm.Opcodes.ICONST_3;
import static org.objectweb.asm.Opcodes.ICONST_4;
import static org.objectweb.asm.Opcodes.ICONST_5;
import static org.objectweb.asm.Opcodes.IFEQ;
import static org.objectweb.asm.Opcodes.IFNE;
import static org.objectweb.asm.Opcodes.IFNULL;
import static org.objectweb.asm.Opcodes.INTEGER;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.PUTFIELD;
import static org.objectweb.asm.Opcodes.PUTSTATIC;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.SIPUSH;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Collection;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

class ResourceBundleTransformer implements ClassFileTransformer {

    private static final String INTERNAL_PREFIX = "__agent__";

    private final String pattern;

    private final Collection<String> includes;

    private final Collection<String> excludes;

    ResourceBundleTransformer(final String pattern, final Collection<String> includes, final Collection<String> excludes) {
        this.pattern = ofNullable(pattern).orElse("[$locale] $value");
        this.includes = includes;
        this.excludes = excludes;
    }

    @Override
    public byte[] transform(final ClassLoader loader, final String className, final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain, final byte[] classfileBuffer) {
        if ("java/util/ResourceBundle".equals(className)) {
            return decorate(classfileBuffer);
        }
        return classfileBuffer;
    }

    private byte[] decorate(final byte[] classfileBuffer) {
        try {
            final ClassReader reader = new ClassReader(classfileBuffer);
            final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
            reader.accept(new ResourceBundleClassVisitor(pattern, writer, includes, excludes), ClassReader.SKIP_FRAMES);
            Log.info("Transformed ResourceBundle");
            return writer.toByteArray();
        } catch (final Throwable e) {
            e.printStackTrace(); // no logger
            return classfileBuffer;
        }
    }

    /**
     * {@code
     *     // original impl just renamed
     *     private static java.util.ResourceBundle __agent__getBundleImpl(String baseName, Locale locale,
     *                                                           ClassLoader loader, java.util.ResourceBundle.Control
     *                                                           control) {
     *         return null;
     * }
     *
     * // original impl just renamed
     * public final Object _agent_getObject(String key) {
     * return null;
     * }
     *
     * private static boolean _agent_isIncluded(final String baseName) {
     * return baseName == null
     * || ((EXCLUDES == null || !_agent_matches(baseName, EXCLUDES)) && (INCLUDES == null || _agent_matches(baseName,
     * INCLUDES)));
     * }
     *
     * private static boolean _agent_matches(final String baseName, final Collection<String> prefixes) {
     * return baseName != null && prefixes.stream().anyMatch(baseName::startsWith);
     * }
     *
     * private static ResourceBundle getBundleImpl(String baseName, Locale locale,
     * ClassLoader loader, java.util.ResourceBundle.Control
     * control) {
     * ResourceBundle bundle = __agent__getBundleImpl(baseName, locale, loader, control);
     * if (bundle != null && baseName != null) {
     * bundle._agent_instrumented = _agent_isIncluded(baseName);
     * }
     * return bundle;
     * }
     *
     * public final Object getObject(String key) {
     * final Object value = _agent_getObject(key);
     * if (_agent_instrumented) {
     * return doFormat(value);
     * }
     * return value;
     * }
     *
     * private Object doFormat(final Object value) {
     * if (String.class.isInstance(value)) {
     * return doFormatString(String.class.cast(value));
     * }
     * if (String[].class.isInstance(value)) {
     * return Stream.of(String[].class.cast(value))
     * .map(this::doFormatString)
     * .toArray(String[]::new);
     * }
     * return value;
     * }
     *
     * private String doFormatString(final String value) {
     * return pattern.replace("$value", value)
     * .replace("$locale", ofNullable(getLocale()).map(Locale::toString).filter(it -> !it.isEmpty()).orElse("default"))
     * .replace("$lang", ofNullable(getLocale()).map(Locale::getLanguage).filter(it -> !it.isEmpty()
     * ).orElse("default"))
     * .replace("$base", getBaseBundleName());
     * }
     * }
     */
    private static class ResourceBundleClassVisitor extends ClassVisitor {

        private final String pattern;

        private final Collection<String> includes;

        private final Collection<String> excludes;

        private String owner;

        private boolean cinitSeen;

        private MethodMeta getObjectMeta;

        private MethodMeta getBundleImplMeta;

        private ResourceBundleClassVisitor(final String pattern, final ClassVisitor visitor, final Collection<String> includes,
                final Collection<String> excludes) {
            super(ASM7, visitor);
            this.pattern = pattern;
            this.includes = includes;
            this.excludes = excludes;
        }

        @Override
        public void visit(final int version, final int access, final String name, final String signature, final String superName,
                final String[] interfaces) {
            this.owner = name;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(final int access, final String name, final String descriptor, final String signature,
                final String[] exceptions) {
            if ("getObject".equals(name)) {
                if (this.getObjectMeta != null) {
                    throw new IllegalStateException("Ambiguous method " + name);
                }
                this.getObjectMeta = new MethodMeta(access, name, descriptor, signature, exceptions);
                return super.visitMethod(access, INTERNAL_PREFIX + name, descriptor, signature, exceptions);
            }
            if ("getBundleImpl".equals(name)) {
                if (this.getBundleImplMeta != null) {
                    throw new IllegalStateException("Ambiguous method " + name);
                }
                this.getBundleImplMeta = new MethodMeta(access, name, descriptor, signature, exceptions);
                return super.visitMethod(access, INTERNAL_PREFIX + name, descriptor, signature, exceptions);
            }
            if ("<clinit>".equals(name)) {
                cinitSeen = true;
                return new MethodVisitor(ASM7, super.visitMethod(access, name, descriptor, signature, exceptions)) {

                    @Override
                    public void visitCode() {
                        super.visitCode();
                        setIncludesExcludes(mv);
                    }

                    @Override
                    public void visitMaxs(final int maxStack, final int maxLocals) {
                        super.visitMaxs(-1, -1);
                    }
                };
            }
            return super.visitMethod(access, name, descriptor, signature, exceptions);
        }

        @Override
        public void visitEnd() {
            if (getObjectMeta == null || getBundleImplMeta == null) { // validate our state
                throw new IllegalStateException("No getObject or getBundleImpl found");
            }

            addCustomStaticFields();
            addCustomField();
            createIsIncluded();
            createDoFormat();
            createDelegatingGetObject();
            createDelegatingGetBundleImpl();

            if (!cinitSeen) {
                final MethodVisitor mv = super.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
                mv.visitCode();
                setIncludesExcludes(mv);
                mv.visitInsn(RETURN);
                mv.visitMaxs(-1, -1);
                mv.visitEnd();
            }

            super.visitEnd();
        }

        private void addCustomStaticFields() {
            visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC, INTERNAL_PREFIX + "INCLUDES", "Ljava/util/Collection;",
                    "Ljava/util/Collection<Ljava/lang/String;>;", null).visitEnd();
            visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC, INTERNAL_PREFIX + "EXCLUDES", "Ljava/util/Collection;",
                    "Ljava/util/Collection<Ljava/lang/String;>;", null).visitEnd();
        }

        private void addCustomField() {
            visitField(ACC_PRIVATE, INTERNAL_PREFIX + "instrumented", "Z", null, null).visitEnd();
        }

        private void setIncludesExcludes(final MethodVisitor mv) {
            createArray(includes, mv);
            mv.visitFieldInsn(PUTSTATIC, owner, INTERNAL_PREFIX + "INCLUDES", "Ljava/util/Collection;");

            createArray(excludes, mv);
            mv.visitFieldInsn(PUTSTATIC, owner, INTERNAL_PREFIX + "EXCLUDES", "Ljava/util/Collection;");
        }

        private void createArray(final Collection<String> values, final MethodVisitor mv) {
            if (values == null || values.isEmpty()) {
                mv.visitInsn(ACONST_NULL);
            } else {
                visitInt(mv, values.size());
                mv.visitTypeInsn(ANEWARRAY, "java/lang/String");
                int idx = 0;
                for (final String value : values) {
                    mv.visitInsn(DUP);
                    visitInt(mv, idx);
                    mv.visitLdcInsn(value);
                    mv.visitInsn(AASTORE);
                    idx++;
                }
                mv.visitMethodInsn(INVOKESTATIC, "java/util/Arrays", "asList", "([Ljava/lang/Object;)Ljava/util/List;", false);
            }
        }

        private void visitInt(final MethodVisitor mv, final int value) {
            switch (value) {
            case 0:
                mv.visitInsn(ICONST_0);
                break;
            case 1:
                mv.visitInsn(ICONST_1);
                break;
            case 2:
                mv.visitInsn(ICONST_2);
                break;
            case 3:
                mv.visitInsn(ICONST_3);
                break;
            case 4:
                mv.visitInsn(ICONST_4);
                break;
            case 5:
                mv.visitInsn(ICONST_5);
                break;
            default:
                if (value > 5 && value <= 255) {
                    mv.visitInsn(BIPUSH);
                } else {
                    mv.visitInsn(SIPUSH);
                }
            }
        }

        private void createIsIncluded() {
            {
                final MethodVisitor mv = visitMethod(ACC_PRIVATE + ACC_STATIC, INTERNAL_PREFIX + "matches",
                        "(Ljava/lang/String;Ljava/util/Collection;)Z",
                        "(Ljava/lang/String;Ljava/util/Collection<Ljava/lang/String;>;)Z", null);
                mv.visitCode();
                mv.visitVarInsn(ALOAD, 0);
                final Label l0 = new Label();
                mv.visitJumpInsn(IFNULL, l0);
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Collection", "stream", "()Ljava/util/stream/Stream;", true);
                mv.visitVarInsn(ALOAD, 0);
                mv.visitInsn(DUP);
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
                mv.visitInsn(POP);
                mv.visitInvokeDynamicInsn("test", "(Ljava/lang/String;)Ljava/util/function/Predicate;", new Handle(H_INVOKESTATIC,
                        "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;"),
                        Type.getType("(Ljava/lang/Object;)Z"),
                        new Handle(H_INVOKEVIRTUAL, "java/lang/String", "startsWith", "(Ljava/lang/String;)Z", false),
                        Type.getType("(Ljava/lang/String;)Z"));
                mv.visitMethodInsn(INVOKEINTERFACE, "java/util/stream/Stream", "anyMatch", "(Ljava/util/function/Predicate;)Z",
                        true);
                mv.visitJumpInsn(IFEQ, l0);
                mv.visitInsn(ICONST_1);
                final Label l1 = new Label();
                mv.visitJumpInsn(GOTO, l1);
                mv.visitLabel(l0);
                mv.visitFrame(F_SAME, 0, null, 0, null);
                mv.visitInsn(ICONST_0);
                mv.visitLabel(l1);
                mv.visitFrame(F_SAME1, 0, null, 1, new Object[] { INTEGER });
                mv.visitInsn(IRETURN);
                mv.visitMaxs(-1, -1);
                mv.visitEnd();
            }
            {
                final MethodVisitor mv = visitMethod(ACC_PRIVATE + ACC_STATIC, INTERNAL_PREFIX + "isIncluded",
                        "(Ljava/lang/String;)Z", null, null);
                mv.visitCode();
                mv.visitVarInsn(ALOAD, 0);
                Label l0 = new Label();
                mv.visitJumpInsn(IFNULL, l0);
                mv.visitFieldInsn(GETSTATIC, owner, INTERNAL_PREFIX + "EXCLUDES", "Ljava/util/Collection;");
                Label l1 = new Label();
                mv.visitJumpInsn(IFNULL, l1);
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETSTATIC, owner, INTERNAL_PREFIX + "EXCLUDES", "Ljava/util/Collection;");
                mv.visitMethodInsn(INVOKESTATIC, owner, INTERNAL_PREFIX + "matches",
                        "(Ljava/lang/String;Ljava/util/Collection;)Z", false);
                Label l2 = new Label();
                mv.visitJumpInsn(IFNE, l2);
                mv.visitLabel(l1);
                mv.visitFrame(F_SAME, 0, null, 0, null);
                mv.visitFieldInsn(GETSTATIC, owner, INTERNAL_PREFIX + "INCLUDES", "Ljava/util/Collection;");
                mv.visitJumpInsn(IFNULL, l0);
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETSTATIC, owner, INTERNAL_PREFIX + "INCLUDES", "Ljava/util/Collection;");
                mv.visitMethodInsn(INVOKESTATIC, owner, INTERNAL_PREFIX + "matches",
                        "(Ljava/lang/String;Ljava/util/Collection;)Z", false);
                mv.visitJumpInsn(IFEQ, l2);
                mv.visitLabel(l0);
                mv.visitFrame(F_SAME, 0, null, 0, null);
                mv.visitInsn(ICONST_1);
                Label l3 = new Label();
                mv.visitJumpInsn(GOTO, l3);
                mv.visitLabel(l2);
                mv.visitFrame(F_SAME, 0, null, 0, null);
                mv.visitInsn(ICONST_0);
                mv.visitLabel(l3);
                mv.visitFrame(F_SAME1, 0, null, 1, new Object[] { INTEGER });
                mv.visitInsn(IRETURN);
                mv.visitMaxs(-1, -1);
                mv.visitEnd();
            }
        }

        private void createDoFormat() {
            {
                final MethodVisitor mv = visitMethod(ACC_PRIVATE, INTERNAL_PREFIX + "doFormat",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
                mv.visitCode();
                mv.visitLdcInsn(Type.getType("Ljava/lang/String;"));
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "isInstance", "(Ljava/lang/Object;)Z", false);
                final Label l0 = new Label();
                mv.visitJumpInsn(IFEQ, l0);
                mv.visitVarInsn(ALOAD, 0);
                mv.visitLdcInsn(Type.getType("Ljava/lang/String;"));
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "cast", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                mv.visitTypeInsn(CHECKCAST, "java/lang/String");
                mv.visitMethodInsn(INVOKESPECIAL, owner, INTERNAL_PREFIX + "doFormatString",
                        "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitInsn(ARETURN);
                mv.visitLabel(l0);
                mv.visitFrame(F_SAME, 0, null, 0, null);
                mv.visitLdcInsn(Type.getType("[Ljava/lang/String;"));
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "isInstance", "(Ljava/lang/Object;)Z", false);
                Label l1 = new Label();
                mv.visitJumpInsn(IFEQ, l1);
                mv.visitLdcInsn(Type.getType("[Ljava/lang/String;"));
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "cast", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                mv.visitTypeInsn(CHECKCAST, "[Ljava/lang/Object;");
                mv.visitMethodInsn(INVOKESTATIC, "java/util/stream/Stream", "of",
                        "([Ljava/lang/Object;)Ljava/util/stream/Stream;", true);
                mv.visitVarInsn(ALOAD, 0);
                mv.visitInvokeDynamicInsn("apply", "(L" + owner + ";)Ljava/util/function/Function;", new Handle(H_INVOKESTATIC,
                        "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                        false), Type.getType("(Ljava/lang/Object;)Ljava/lang/Object;"),
                        new Handle(H_INVOKESPECIAL, owner, INTERNAL_PREFIX + "doFormatString",
                                "(Ljava/lang/String;)Ljava/lang/String;", false),
                        Type.getType("(Ljava/lang/String;)Ljava/lang/String;"));
                mv.visitMethodInsn(INVOKEINTERFACE, "java/util/stream/Stream", "map",
                        "(Ljava/util/function/Function;)Ljava/util/stream/Stream;", true);
                mv.visitInvokeDynamicInsn("apply", "()Ljava/util/function/IntFunction;", new Handle(H_INVOKESTATIC,
                        "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                        false), Type.getType("(I)Ljava/lang/Object;"),
                        new Handle(H_INVOKESTATIC, owner, "lambda$" + INTERNAL_PREFIX + "doFormat$0", "(I)[Ljava/lang/String;", false),
                        Type.getType("(I)[Ljava/lang/String;"));
                mv.visitMethodInsn(INVOKEINTERFACE, "java/util/stream/Stream", "toArray",
                        "(Ljava/util/function/IntFunction;)[Ljava/lang/Object;", true);
                mv.visitInsn(ARETURN);
                mv.visitLabel(l1);
                mv.visitFrame(F_SAME, 0, null, 0, null);
                mv.visitVarInsn(ALOAD, 1);
                mv.visitInsn(ARETURN);
                mv.visitMaxs(-1, -1);
                mv.visitEnd();
            }
            {
                final MethodVisitor mv = visitMethod(ACC_PRIVATE, INTERNAL_PREFIX + "doFormatString",
                        "(Ljava/lang/String;)Ljava/lang/String;", null, null);
                mv.visitCode();
                mv.visitLdcInsn(pattern);
                mv.visitLdcInsn("$value");
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "replace",
                        "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;", false);
                mv.visitLdcInsn("$locale");
                mv.visitVarInsn(ALOAD, 0);
                mv.visitMethodInsn(INVOKESPECIAL, owner, "getLocale", "()Ljava/util/Locale;", false);
                mv.visitMethodInsn(INVOKESTATIC, "java/util/Optional", "ofNullable", "(Ljava/lang/Object;)Ljava/util/Optional;",
                        false);
                mv.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;", new Handle(H_INVOKESTATIC,
                        "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
                        Type.getType("(Ljava/lang/Object;)Ljava/lang/Object;"),
                        new Handle(H_INVOKEVIRTUAL, "java/util/Locale", "toString", "()Ljava/lang/String;", false),
                        Type.getType("(Ljava/util/Locale;)Ljava/lang/String;"));
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/Optional", "map",
                        "(Ljava/util/function/Function;)Ljava/util/Optional;", false);
                mv.visitInvokeDynamicInsn("test", "()Ljava/util/function/Predicate;", new Handle(H_INVOKESTATIC,
                        "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
                        Type.getType("(Ljava/lang/Object;)Z"),
                        new Handle(H_INVOKESTATIC, owner, "lambda$" + INTERNAL_PREFIX + "doFormatString$0", "(Ljava/lang/String;)Z", false),
                        Type.getType("(Ljava/lang/String;)Z"));
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/Optional", "filter",
                        "(Ljava/util/function/Predicate;)Ljava/util/Optional;", false);
                mv.visitLdcInsn("default");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/Optional", "orElse", "(Ljava/lang/Object;)Ljava/lang/Object;",
                        false);
                mv.visitTypeInsn(CHECKCAST, "java/lang/CharSequence");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "replace",
                        "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;", false);
                mv.visitLdcInsn("$lang");
                mv.visitVarInsn(ALOAD, 0);
                mv.visitMethodInsn(INVOKESPECIAL, owner, "getLocale", "()Ljava/util/Locale;", false);
                mv.visitMethodInsn(INVOKESTATIC, "java/util/Optional", "ofNullable", "(Ljava/lang/Object;)Ljava/util/Optional;",
                        false);
                mv.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;", new Handle(H_INVOKESTATIC,
                        "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
                        Type.getType("(Ljava/lang/Object;)Ljava/lang/Object;"),
                        new Handle(H_INVOKEVIRTUAL, "java/util/Locale", "getLanguage", "()Ljava/lang/String;", false),
                        Type.getType("(Ljava/util/Locale;)Ljava/lang/String;"));
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/Optional", "map",
                        "(Ljava/util/function/Function;)Ljava/util/Optional;", false);
                mv.visitInvokeDynamicInsn("test", "()Ljava/util/function/Predicate;", new Handle(H_INVOKESTATIC,
                        "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
                        Type.getType("(Ljava/lang/Object;)Z"),
                        new Handle(H_INVOKESTATIC, owner, "lambda$" + INTERNAL_PREFIX + "doFormatString$1", "(Ljava/lang/String;)Z", false),
                        Type.getType("(Ljava/lang/String;)Z"));
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/Optional", "filter",
                        "(Ljava/util/function/Predicate;)Ljava/util/Optional;", false);
                mv.visitLdcInsn("default");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/Optional", "orElse", "(Ljava/lang/Object;)Ljava/lang/Object;",
                        false);
                mv.visitTypeInsn(CHECKCAST, "java/lang/CharSequence");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "replace",
                        "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;", false);
                mv.visitLdcInsn("$base");
                mv.visitVarInsn(ALOAD, 0);
                mv.visitMethodInsn(INVOKESPECIAL, owner, "getBaseBundleName", "()Ljava/lang/String;", false);
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "replace",
                        "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;", false);
                mv.visitInsn(ARETURN);
                mv.visitMaxs(-1, -1);
                mv.visitEnd();
            }
            {
                final MethodVisitor mv = visitMethod(ACC_PRIVATE + ACC_STATIC + ACC_SYNTHETIC, "lambda$" + INTERNAL_PREFIX + "doFormatString$0", "(Ljava/lang/String;)Z", null, null);
                mv.visitCode();
                mv.visitVarInsn(ALOAD, 0);
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "isEmpty", "()Z", false);
                final Label l0 = new Label();
                mv.visitJumpInsn(IFNE, l0);
                mv.visitInsn(ICONST_1);
                final Label l1 = new Label();
                mv.visitJumpInsn(GOTO, l1);
                mv.visitLabel(l0);
                mv.visitFrame(F_SAME, 0, null, 0, null);
                mv.visitInsn(ICONST_0);
                mv.visitLabel(l1);
                mv.visitFrame(F_SAME1, 0, null, 1, new Object[] {INTEGER});
                mv.visitInsn(IRETURN);
                mv.visitMaxs(-1, -1);
                mv.visitEnd();
            }
            {
                final MethodVisitor mv = visitMethod(ACC_PRIVATE + ACC_STATIC + ACC_SYNTHETIC, "lambda$" + INTERNAL_PREFIX + "doFormatString$1", "(Ljava/lang/String;)Z", null, null);
                mv.visitCode();
                mv.visitVarInsn(ALOAD, 0);
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "isEmpty", "()Z", false);
                final Label l0 = new Label();
                mv.visitJumpInsn(IFNE, l0);
                mv.visitInsn(ICONST_1);
                final Label l1 = new Label();
                mv.visitJumpInsn(GOTO, l1);
                mv.visitLabel(l0);
                mv.visitFrame(F_SAME, 0, null, 0, null);
                mv.visitInsn(ICONST_0);
                mv.visitLabel(l1);
                mv.visitFrame(F_SAME1, 0, null, 1, new Object[] {INTEGER});
                mv.visitInsn(IRETURN);
                mv.visitMaxs(-1, -1);
                mv.visitEnd();
            }
        }

        private void createDelegatingGetBundleImpl() {
            final Label label = new Label();
            final MethodVisitor mv = super.visitMethod(getBundleImplMeta.access, getBundleImplMeta.name,
                    getBundleImplMeta.descriptor, getBundleImplMeta.signature, getBundleImplMeta.exceptions);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitMethodInsn(INVOKESTATIC, owner, INTERNAL_PREFIX + "getBundleImpl",
                    "(Ljava/lang/String;Ljava/util/Locale;Ljava/lang/ClassLoader;Ljava/util/ResourceBundle$Control;)L" + owner
                            + ";",
                    false);
            mv.visitVarInsn(ASTORE, 4);
            mv.visitVarInsn(ALOAD, 4);
            mv.visitJumpInsn(IFNULL, label);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitJumpInsn(IFNULL, label);
            mv.visitVarInsn(ALOAD, 4);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC, owner, INTERNAL_PREFIX + "isIncluded", "(Ljava/lang/String;)Z", false);
            mv.visitFieldInsn(PUTFIELD, owner, INTERNAL_PREFIX + "instrumented", "Z");
            mv.visitLabel(label);
            mv.visitFrame(F_APPEND, 1, new Object[] { owner }, 0, null);
            mv.visitVarInsn(ALOAD, 4);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(-1, -1);
            mv.visitEnd();
        }

        private void createDelegatingGetObject() {
            final MethodVisitor getObject = super.visitMethod(getObjectMeta.access, getObjectMeta.name, getObjectMeta.descriptor,
                    getObjectMeta.signature, getObjectMeta.exceptions);
            getObject.visitCode();
            getObject.visitVarInsn(ALOAD, 0);
            getObject.visitVarInsn(ALOAD, 1);
            getObject.visitMethodInsn(INVOKEVIRTUAL, owner, INTERNAL_PREFIX + "getObject",
                    "(Ljava/lang/String;)Ljava/lang/Object;", false);
            getObject.visitVarInsn(ASTORE, 2);
            getObject.visitVarInsn(ALOAD, 0);
            getObject.visitFieldInsn(GETFIELD, owner, INTERNAL_PREFIX + "instrumented", "Z");
            final Label ifLabel = new Label();
            getObject.visitJumpInsn(IFEQ, ifLabel);
            getObject.visitVarInsn(ALOAD, 0);
            getObject.visitVarInsn(ALOAD, 2);
            getObject.visitMethodInsn(INVOKESPECIAL, owner, INTERNAL_PREFIX + "doFormat",
                    "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            getObject.visitInsn(ARETURN);
            getObject.visitLabel(ifLabel);
            getObject.visitFrame(F_APPEND, 1, new Object[] { "java/lang/Object" }, 0, null);
            getObject.visitVarInsn(ALOAD, 2);
            getObject.visitInsn(ARETURN);
            getObject.visitMaxs(-1, -1);
            getObject.visitEnd();
        }
    }

    private static final class MethodMeta {

        private final int access;

        private final String name;

        private final String descriptor;

        private final String signature;

        private final String[] exceptions;

        private MethodMeta(final int access, final String name, final String descriptor, final String signature,
                final String[] exceptions) {
            this.access = access;
            this.name = name;
            this.descriptor = descriptor;
            this.signature = signature;
            this.exceptions = exceptions;
        }
    }
}
