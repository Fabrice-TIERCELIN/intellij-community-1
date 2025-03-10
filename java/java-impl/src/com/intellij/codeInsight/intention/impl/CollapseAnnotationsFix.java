// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.intention.impl;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.QuickFixFactory;
import com.intellij.java.JavaBundle;
import com.intellij.java.analysis.JavaAnalysisBundle;
import com.intellij.modcommand.ActionContext;
import com.intellij.modcommand.ModPsiUpdater;
import com.intellij.modcommand.PsiUpdateModCommandAction;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.ArrayUtil;
import com.siyeh.ig.psiutils.CommentTracker;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class CollapseAnnotationsFix extends PsiUpdateModCommandAction<PsiAnnotation> {
  private CollapseAnnotationsFix(PsiAnnotation annotation) {
    super(annotation);
  }

  @Override
  protected @NotNull Presentation getPresentation(@NotNull ActionContext context, @NotNull PsiAnnotation element) {
    return Presentation.of(getFamilyName()).withFixAllOption(this);
  }

  @Override
  protected void invoke(@NotNull ActionContext context, @NotNull PsiAnnotation annotation, @NotNull ModPsiUpdater updater) {
    PsiNameValuePair attribute = ArrayUtil.getFirstElement(annotation.getParameterList().getAttributes());
    if (attribute == null) return;
    PsiAnnotationMemberValue origValue = attribute.getValue();
    if (origValue == null) return;
    List<PsiAnnotation> annotations = findCollapsibleAnnotations(annotation, attribute);
    List<PsiAnnotationMemberValue> values = new ArrayList<>();
    CommentTracker ct = new CommentTracker();
    for (PsiAnnotation anno : annotations) {
      PsiAnnotationMemberValue value = anno.getParameterList().getAttributes()[0].getValue();
      if (value instanceof PsiArrayInitializerMemberValue) {
        PsiAnnotationMemberValue[] initializers = ((PsiArrayInitializerMemberValue)value).getInitializers();
        for (PsiAnnotationMemberValue initializer : initializers) {
          values.add(ct.markUnchanged(initializer));
        }
      }
      else if (value != null) {
        values.add(ct.markUnchanged(value));
      }
      if (anno != annotation) {
        ct.delete(anno);
      }
    }
    String newValue = StreamEx.of(values).map(PsiElement::getText).joining(", ", "{", "}");
    PsiAnnotation dummy = JavaPsiFacade.getElementFactory(context.project())
      .createAnnotationFromText("@x(" + newValue + ")", origValue);
    ct.replaceAndRestoreComments(origValue, Objects.requireNonNull(dummy.getParameterList().getAttributes()[0].getValue()));
  }

  @Nls(capitalization = Nls.Capitalization.Sentence)
  @NotNull
  @Override
  public String getFamilyName() {
    return JavaBundle.message("intention.text.collapse.repeating.annotations");
  }

  private static List<PsiAnnotation> findCollapsibleAnnotations(PsiAnnotation annotation, PsiNameValuePair attribute) {
    PsiAnnotationOwner owner = annotation.getOwner();
    String name = annotation.getQualifiedName();
    if (owner == null || name == null) {
      return Collections.emptyList();
    }
    List<PsiAnnotation> annotations = new ArrayList<>();
    for (PsiAnnotation other : owner.getAnnotations()) {
      if (name.equals(other.getQualifiedName())) {
        PsiNameValuePair[] otherAttributes = other.getParameterList().getAttributes();
        if (otherAttributes.length == 1) {
          PsiNameValuePair otherAttribute = otherAttributes[0];
          if (otherAttribute.getAttributeName().equals(attribute.getAttributeName())) {
            annotations.add(other);
          }
        }
      }
    }
    return annotations;
  }

  @Nullable
  public static IntentionAction from(PsiAnnotation annotation) {
    PsiAnnotationOwner owner = annotation.getOwner();
    String name = annotation.getQualifiedName();
    if (owner == null || name == null) return null;
    PsiNameValuePair[] attributes = annotation.getParameterList().getAttributes();
    if (attributes.length == 0) {
      return QuickFixFactory.getInstance().createDeleteFix(annotation, JavaAnalysisBundle.message("intention.text.remove.annotation"));
    }
    if (attributes.length != 1) return null;
    PsiNameValuePair attribute = attributes[0];
    if (attribute.getValue() == null) return null;
    PsiMethod annoMethod = findAttributeMethod(attribute);
    if (annoMethod == null || !(annoMethod.getReturnType() instanceof PsiArrayType)) return null;
    List<PsiAnnotation> annotations = findCollapsibleAnnotations(annotation, attribute);
    if (annotations.size() < 2) return null;
    return new CollapseAnnotationsFix(annotation).asIntention();
  }

  @Nullable
  private static PsiMethod findAttributeMethod(PsiNameValuePair attribute) {
    PsiReference ref = attribute.getReference();
    if (ref == null) return null;
    PsiElement target = ref.resolve();
    if (PsiUtil.isAnnotationMethod(target)) {
      return (PsiAnnotationMethod)target;
    }
    return null;
  }
}
