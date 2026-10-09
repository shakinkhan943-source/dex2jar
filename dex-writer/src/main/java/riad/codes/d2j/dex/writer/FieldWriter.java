/*
 * dex2jar - Tools to work with android .dex and java .class files
 * Copyright (c) 2009-2013 Panxiaobo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package riad.codes.d2j.dex.writer;

import riad.codes.d2j.Visibility;
import riad.codes.d2j.dex.writer.item.AnnotationItem;
import riad.codes.d2j.dex.writer.item.AnnotationSetItem;
import riad.codes.d2j.dex.writer.item.ClassDataItem;
import riad.codes.d2j.dex.writer.item.ConstPool;
import riad.codes.d2j.visitors.DexAnnotationVisitor;
import riad.codes.d2j.visitors.DexFieldVisitor;

/*package*/class FieldWriter extends DexFieldVisitor {
    final public ConstPool cp;
    private final ClassDataItem.EncodedField encodedField;

    public FieldWriter(ClassDataItem.EncodedField encodedField, ConstPool cp) {
        this.encodedField = encodedField;
        this.cp = cp;
    }

    @Override
    public DexAnnotationVisitor visitAnnotation(String name,
                                                Visibility visibility) {
        final AnnotationItem annItem = new AnnotationItem(cp.uniqType(name),
                visibility);
        AnnotationSetItem asi = encodedField.annotationSetItem;
        if (asi == null) {
            asi = new AnnotationSetItem();
            encodedField.annotationSetItem = asi;
        }
        asi.annotations.add(annItem);
        return new AnnotationWriter(annItem.annotation.elements, cp);
    }
}
