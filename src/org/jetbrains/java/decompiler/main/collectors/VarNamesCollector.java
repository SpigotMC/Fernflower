// Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.java.decompiler.main.collectors;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarProcessor;

public class VarNamesCollector {

  private final Set<VarProcessor.MutableString> usedNames = new HashSet<>();

  public VarNamesCollector() { }

  public VarNamesCollector(Collection<VarProcessor.MutableString> setNames) {
    usedNames.addAll(setNames);
  }

  public void addName(VarProcessor.MutableString value) {
    usedNames.add(value);
  }

  public VarProcessor.MutableString getFreeName(int index) {
    return getFreeName(new VarProcessor.MutableString("var" + index));
  }

  public VarProcessor.MutableString getFreeName(VarProcessor.MutableString proposition) {
    while (usedNames.contains(proposition)) {
      proposition.string += "x";
    }
    usedNames.add(proposition);
    return proposition;
  }
}
