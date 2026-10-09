package riad.codes.d2j.node.insn;

import riad.codes.d2j.DexLabel;
import riad.codes.d2j.reader.Op;
import riad.codes.d2j.visitors.DexCodeVisitor;

public class SparseSwitchStmtNode extends BaseSwitchStmtNode {

    public final int[] cases;

    public SparseSwitchStmtNode(Op op, int a, int[] cases, DexLabel[] labels) {
        super(op, a, labels);
        this.cases = cases;
    }

    @Override
    public void accept(DexCodeVisitor cv) {
        cv.visitSparseSwitchStmt(op, a, cases, labels);
    }
}
