package io.github.yoavgeva.designguard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/** Only accessors, and the methods of their class they call, need a data-flow analysis of their changes. */
class AccessorChangesTest {

    private static final int METHODS = 5_000;
    private static final int BLOCKS = 60;
    private static final String OWNER = "shop/Ledger";
    private static final String NAME = "name";
    private static final String STRING = "Ljava/lang/String;";

    @Test
    void readsLargeClassesWithoutAnalyzingMethodsNoAccessorCalls() {
        ClassNode ledger = ledger();

        Map<String, ?> changes = assertTimeoutPreemptively(Duration.ofMillis(300), () -> AccessorChanges.of(ledger));

        assertThat(changes).containsOnlyKeys("getName()" + STRING);
    }

    /** A class with many long methods that write a field behind null checks, and one accessor that writes it too. */
    private static ClassNode ledger() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, NAME, STRING, null, null).visitEnd();
        for (int index = 0; index < METHODS; index++) {
            recorder(writer.visitMethod(Opcodes.ACC_PUBLIC, "record" + index, "()V", null, null));
        }
        accessor(writer.visitMethod(Opcodes.ACC_PUBLIC, "getName", "()" + STRING, null, null));
        writer.visitEnd();
        ClassNode node = new ClassNode();
        new ClassReader(writer.toByteArray()).accept(node, ClassReader.SKIP_FRAMES);
        return node;
    }

    /** {@code if (name == null) name = "entry0"; if (name == null) name = "entry1"; ...} */
    private static void recorder(MethodVisitor method) {
        method.visitCode();
        for (int block = 0; block < BLOCKS; block++) {
            guardedWrite(method, "entry" + block);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void guardedWrite(MethodVisitor method, String value) {
        Label done = new Label();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, OWNER, NAME, STRING);
        method.visitJumpInsn(Opcodes.IFNONNULL, done);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitLdcInsn(value);
        method.visitFieldInsn(Opcodes.PUTFIELD, OWNER, NAME, STRING);
        method.visitLabel(done);
    }

    /** {@code getName() { name = "changed"; return name; }} */
    private static void accessor(MethodVisitor method) {
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitLdcInsn("changed");
        method.visitFieldInsn(Opcodes.PUTFIELD, OWNER, NAME, STRING);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, OWNER, NAME, STRING);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }
}
