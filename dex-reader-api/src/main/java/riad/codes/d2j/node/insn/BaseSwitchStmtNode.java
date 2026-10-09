package riad.codes.d2j.node.insn;

import riad.codes.d2j.DexLabel;
import riad.codes.d2j.reader.Op;

public abstract class BaseSwitchStmtNode extends DexStmtNode {

    public final int a;
    public final DexLabel[] labels;

    protected BaseSwitchStmtNode(Op op, int a, DexLabel[] labels) {
        super(op);
        this.a = a;
        this.labels = labels;
    }
}
