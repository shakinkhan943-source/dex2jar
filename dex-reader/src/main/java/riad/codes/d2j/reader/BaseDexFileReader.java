package riad.codes.d2j.reader;

import riad.codes.d2j.visitors.DexFileVisitor;

import java.util.List;

public interface BaseDexFileReader {

    int getDexVersion();

    void accept(DexFileVisitor dv);

    List<String> getClassNames();

    void accept(DexFileVisitor dv, int config);

    void accept(DexFileVisitor dv, int classIdx, int config);
}
