package riad.codes.d2j.node.insn;

import riad.codes.d2j.reader.Op;
import riad.codes.d2j.visitors.DexCodeVisitor;

public class Stmt0RNode extends DexStmtNode {
    public Stmt0RNode(Op op) {
        super(op);
    }
    @Override
    public void accept(DexCodeVisitor cv) {
        cv.visitStmt0R(op);
    }
}
