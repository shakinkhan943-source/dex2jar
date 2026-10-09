package riad.codes.d2j.node.insn;

import riad.codes.d2j.DexLabel;
import riad.codes.d2j.reader.Op;
import riad.codes.d2j.visitors.DexCodeVisitor;

public class JumpStmtNode extends DexStmtNode {
    public final int a;
    public final int b;
    public final DexLabel label;

    public JumpStmtNode(Op op, int a, int b, DexLabel label) {
        super(op);
        this.a = a;
        this.b = b;
        this.label = label;
    }

    @Override
    public void accept(DexCodeVisitor cv) {
        cv.visitJumpStmt(op, a, b, label);
    }
}
