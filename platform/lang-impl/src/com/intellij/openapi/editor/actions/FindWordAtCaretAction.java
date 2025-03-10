/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.openapi.editor.actions;

import com.intellij.find.FindUtil;
import com.intellij.find.impl.livePreview.SearchResults;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.remoting.ActionRemoteBehaviorSpecification;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorAction;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static com.intellij.openapi.editor.actions.IncrementalFindAction.SEARCH_DISABLED;

public class FindWordAtCaretAction extends EditorAction implements ActionRemoteBehaviorSpecification.Frontend {
  protected static class Handler extends EditorActionHandler {
    private final SearchResults.Direction myDirection;

    protected Handler(@NotNull  SearchResults.Direction direction) {
      super();
      myDirection = direction;
    }

    @Override
    public void doExecute(@NotNull Editor editor, @Nullable Caret caret, DataContext dataContext) {
      Project project = CommonDataKeys.PROJECT.getData(dataContext);
      FindUtil.findWordAtCaret(project, editor, myDirection);
    }

    @Override
    public boolean isEnabledForCaret(@NotNull Editor editor, @NotNull Caret caret, DataContext dataContext) {
      Project project = CommonDataKeys.PROJECT.getData(dataContext);
      return project != null && !SEARCH_DISABLED.get(editor, false);
    }
  }

  public FindWordAtCaretAction() {
    this(new Handler(SearchResults.Direction.DOWN));
  }

  protected FindWordAtCaretAction(Handler defaultHandler) {
    super(defaultHandler);
  }
}
