package riad.codes.dex2jar.ir.test;

import riad.codes.dex2jar.ir.stmt.Stmt;
import riad.codes.dex2jar.ir.stmt.Stmts;
import riad.codes.dex2jar.ir.ts.DeadCodeTransformer;
import org.junit.Assert;
import org.junit.Test;

public class DeadCodeTrnasformerTest extends BaseTransformerTest<DeadCodeTransformer> {
    @Test
    public void test09DeadCode() {
        Stmt ret = addStmt(Stmts.nReturnVoid());
        Stmt lb = addStmt(newLabel());
        addStmt(Stmts.nReturnVoid());
        transform();
        Assert.assertSame(ret, method.stmts.getFirst());
        Assert.assertSame(ret, method.stmts.getLast());
    }
}
