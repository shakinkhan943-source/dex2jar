package riad.codes.dex2jar.ir.test;


import riad.codes.dex2jar.ir.expr.Exprs;
import org.junit.Assert;
import org.junit.Test;

public class ConstantStringTest {
    @Test
    public void test() {
        String s = Exprs.nString("a\nb").toString();
        Assert.assertEquals("\"a\\nb\"", s);
    }
}
