// Copyright (C) 2012 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.git.strategy;

import com.google.common.collect.Lists;
import com.google.gerrit.common.TimeUtil;
import com.google.gerrit.extensions.restapi.MergeConflictException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.PatchSetApproval;
import com.google.gerrit.server.change.RebaseChangeOp;
import com.google.gerrit.server.git.BatchUpdate;
import com.google.gerrit.server.git.BatchUpdate.ChangeContext;
import com.google.gerrit.server.git.BatchUpdate.Context;
import com.google.gerrit.server.git.BatchUpdate.RepoContext;
import com.google.gerrit.server.git.CodeReviewCommit;
import com.google.gerrit.server.git.IntegrationException;
import com.google.gerrit.server.git.MergeTip;
import com.google.gerrit.server.git.RebaseSorter;
import com.google.gerrit.server.git.UpdateException;
import com.google.gerrit.server.git.validators.CommitValidators;
import com.google.gerrit.server.project.InvalidChangeOperationException;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gwtorm.server.OrmException;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class RebaseIfNecessary extends SubmitStrategy {

  RebaseIfNecessary(SubmitStrategy.Arguments args) {
    super(args);
  }

  @Override
  public MergeTip run(final CodeReviewCommit branchTip,
      final Collection<CodeReviewCommit> toMerge) throws IntegrationException {
    MergeTip mergeTip = new MergeTip(branchTip, toMerge);
    List<CodeReviewCommit> sorted = sort(toMerge);

    boolean first = true;

    try (BatchUpdate u = args.newBatchUpdate(TimeUtil.nowTs())) {
      while (!sorted.isEmpty()) {
        CodeReviewCommit n = sorted.remove(0);
        Change.Id cid = n.change().getId();
        if (first && branchTip == null) {
          u.addOp(cid, new RebaseUnbornRootOp(mergeTip, n));
        } else if (n.getParentCount() == 0) {
          u.addOp(cid, new RebaseRootOp(n));
        } else if (n.getParentCount() == 1) {
          u.addOp(cid, new RebaseOneOp(mergeTip, n));
        } else {
          u.addOp(cid, new RebaseMultipleParentsOp(mergeTip, n));
        }
        first = false;
      }
      u.execute();
    } catch (UpdateException | RestApiException e) {
      if (e.getCause() instanceof IntegrationException) {
        throw new IntegrationException(e.getCause().getMessage(), e);
      }
      throw new IntegrationException(
          "Cannot rebase onto " + args.destBranch, e);
    }
    return mergeTip;
  }

  private class RebaseUnbornRootOp extends BatchUpdate.Op {
    private final MergeTip mergeTip;
    private final CodeReviewCommit toMerge;

    private RebaseUnbornRootOp(MergeTip mergeTip,
        CodeReviewCommit toMerge) {
      this.mergeTip = mergeTip;
      this.toMerge = toMerge;
    }

    @Override
    public void updateRepo(RepoContext ctx) {
      // The branch is unborn. Take fast-forward resolution to create the
      // branch.
      toMerge.setStatusCode(CommitMergeStatus.CLEAN_MERGE);
      mergeTip.moveTipTo(toMerge, toMerge);
      acceptMergeTip(mergeTip);
    }
  }

  private static class RebaseRootOp extends BatchUpdate.Op {
    private final CodeReviewCommit toMerge;

    private RebaseRootOp(CodeReviewCommit toMerge) {
      this.toMerge = toMerge;
    }

    @Override
    public void updateRepo(RepoContext ctx) {
      // Refuse to merge a root commit into an existing branch, we cannot obtain
      // a delta for the cherry-pick to apply.
      toMerge.setStatusCode(CommitMergeStatus.CANNOT_REBASE_ROOT);
    }
  }

  private class RebaseOneOp extends BatchUpdate.Op {
    private final MergeTip mergeTip;
    private final CodeReviewCommit toMerge;

    private RebaseChangeOp rebaseOp;
    private CodeReviewCommit newCommit;

    private RebaseOneOp(MergeTip mergeTip, CodeReviewCommit toMerge) {
      this.mergeTip = mergeTip;
      this.toMerge = toMerge;
    }

    @Override
    public void updateRepo(RepoContext ctx)
        throws IntegrationException, InvalidChangeOperationException,
        RestApiException, IOException, OrmException {
      // TODO(dborowitz): args.rw is needed because it's a CodeReviewRevWalk.
      // When hoisting BatchUpdate into MergeOp, we will need to teach
      // BatchUpdate how to produce CodeReviewRevWalks.
      if (args.mergeUtil.canFastForward(args.mergeSorter,
          mergeTip.getCurrentTip(), args.rw, toMerge)) {
        toMerge.setStatusCode(CommitMergeStatus.CLEAN_MERGE);
        mergeTip.moveTipTo(toMerge, toMerge);
        acceptMergeTip(mergeTip);
        return;
      }

      // Stale read of patch set is ok; see comments in RebaseChangeOp.
      PatchSet origPs = args.psUtil.get(
          ctx.getDb(), toMerge.getControl().getNotes(), toMerge.getPatchsetId());
      rebaseOp = args.rebaseFactory.create(
            toMerge.getControl(), origPs, mergeTip.getCurrentTip().name())
          .setRunHooks(false)
          // Bypass approval copier since we're going to copy all approvals
          // later anyway.
          .setCopyApprovals(false)
          .setValidatePolicy(CommitValidators.Policy.NONE);
      try {
        rebaseOp.updateRepo(ctx);
      } catch (MergeConflictException e) {
        toMerge.setStatusCode(CommitMergeStatus.REBASE_MERGE_CONFLICT);
        throw new IntegrationException(
            "Cannot rebase " + toMerge.name() + ": " + e.getMessage(), e);
      }
      newCommit = args.rw.parseCommit(rebaseOp.getRebasedCommit());
      newCommit.copyFrom(toMerge);
      newCommit.setStatusCode(CommitMergeStatus.CLEAN_REBASE);
      newCommit.setPatchsetId(rebaseOp.getPatchSetId());
      mergeTip.moveTipTo(newCommit, newCommit);
      args.commits.put(mergeTip.getCurrentTip());
      acceptMergeTip(mergeTip);
    }

    @Override
    public void updateChange(ChangeContext ctx) throws NoSuchChangeException,
        InvalidChangeOperationException, OrmException, IOException  {
      if (rebaseOp == null) {
        // Took the fast-forward option, nothing to do.
        return;
      }

      rebaseOp.updateChange(ctx);
      PatchSet.Id newPatchSetId = rebaseOp.getPatchSetId();
      List<PatchSetApproval> approvals = Lists.newArrayList();
      for (PatchSetApproval a : args.approvalsUtil.byPatchSet(ctx.getDb(),
          toMerge.getControl(), toMerge.getPatchsetId())) {
        approvals.add(new PatchSetApproval(newPatchSetId, a));
      }
      args.db.patchSetApprovals().insert(approvals);

      toMerge.change().setCurrentPatchSet(
          args.patchSetInfoFactory.get(args.rw, mergeTip.getCurrentTip(),
              newPatchSetId));
      newCommit.setControl(
          args.changeControlFactory.controlFor(toMerge.change(), args.caller));
    }

    @Override
    public void postUpdate(Context ctx) throws OrmException {
      if (rebaseOp != null) {
        rebaseOp.postUpdate(ctx);
      }
    }
  }

  private class RebaseMultipleParentsOp extends BatchUpdate.Op {
    private final MergeTip mergeTip;
    private final CodeReviewCommit toMerge;

    private RebaseMultipleParentsOp(MergeTip mergeTip,
        CodeReviewCommit toMerge) {
      this.mergeTip = mergeTip;
      this.toMerge = toMerge;
    }

    @Override
    public void updateRepo(RepoContext ctx)
        throws IntegrationException, IOException {
      // There are multiple parents, so this is a merge commit. We don't want
      // to rebase the merge as clients can't easily rebase their history with
      // that merge present and replaced by an equivalent merge with a different
      // first parent. So instead behave as though MERGE_IF_NECESSARY was
      // configured.
      if (args.rw.isMergedInto(mergeTip.getCurrentTip(), toMerge)) {
        mergeTip.moveTipTo(toMerge, toMerge);
        acceptMergeTip(mergeTip);
      } else {
        // TODO(dborowitz): Can't use repo from ctx due to canMergeFlag.
        CodeReviewCommit newTip = args.mergeUtil.mergeOneCommit(
            args.serverIdent, args.serverIdent, args.repo, args.rw,
            args.inserter, args.canMergeFlag, args.destBranch,
            mergeTip.getCurrentTip(), toMerge);
        mergeTip.moveTipTo(newTip, toMerge);
      }
      args.mergeUtil.markCleanMerges(args.rw, args.canMergeFlag,
          mergeTip.getCurrentTip(), args.alreadyAccepted);
      acceptMergeTip(mergeTip);
    }
  }

  private void acceptMergeTip(MergeTip mergeTip) {
    args.alreadyAccepted.add(mergeTip.getCurrentTip());
  }

  private List<CodeReviewCommit> sort(Collection<CodeReviewCommit> toSort)
      throws IntegrationException {
    try {
      List<CodeReviewCommit> result = new RebaseSorter(
          args.rw, args.alreadyAccepted, args.canMergeFlag).sort(toSort);
      Collections.sort(result, CodeReviewCommit.ORDER);
      return result;
    } catch (IOException e) {
      throw new IntegrationException("Commit sorting failed", e);
    }
  }

  static boolean dryRun(SubmitDryRun.Arguments args,
      CodeReviewCommit mergeTip, CodeReviewCommit toMerge)
      throws IntegrationException {
    return !args.mergeUtil.hasMissingDependencies(args.mergeSorter, toMerge)
        && args.mergeUtil.canCherryPick(args.mergeSorter, args.repo, mergeTip,
            args.rw, toMerge);
  }
}
