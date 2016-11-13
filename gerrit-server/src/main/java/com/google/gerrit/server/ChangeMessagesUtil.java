// Copyright (C) 2014 The Android Open Source Project
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

package com.google.gerrit.server;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.ChangeMessage;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.git.BatchUpdate;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.notedb.ChangeUpdate;
import com.google.gerrit.server.notedb.NotesMigration;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Utility functions to manipulate ChangeMessages.
 *
 * <p>These methods either query for and update ChangeMessages in the NoteDb or ReviewDb, depending
 * on the state of the NotesMigration.
 */
@Singleton
public class ChangeMessagesUtil {
  public static final String TAG_ABANDON = "autogenerated:gerrit:abandon";
  public static final String TAG_CHERRY_PICK_CHANGE = "autogenerated:gerrit:cherryPickChange";
  public static final String TAG_DELETE_ASSIGNEE = "autogenerated:gerrit:deleteAssignee";
  public static final String TAG_DELETE_REVIEWER = "autogenerated:gerrit:deleteReviewer";
  public static final String TAG_DELETE_VOTE = "autogenerated:gerrit:deleteVote";
  public static final String TAG_MERGED = "autogenerated:gerrit:merged";
  public static final String TAG_MOVE = "autogenerated:gerrit:move";
  public static final String TAG_RESTORE = "autogenerated:gerrit:restore";
  public static final String TAG_REVERT = "autogenerated:gerrit:revert";
  public static final String TAG_SET_ASSIGNEE = "autogenerated:gerrit:setAssignee";
  public static final String TAG_SET_DESCRIPTION = "autogenerated:gerrit:setPsDescription";
  public static final String TAG_SET_HASHTAGS = "autogenerated:gerrit:setHashtag";
  public static final String TAG_SET_TOPIC = "autogenerated:gerrit:setTopic";
  public static final String TAG_UPLOADED_PATCH_SET = "autogenerated:gerrit:newPatchSet";

  public static ChangeMessage newMessage(
      BatchUpdate.ChangeContext ctx, String body, @Nullable String tag) {
    return newMessage(ctx.getChange().currentPatchSetId(), ctx.getUser(), ctx.getWhen(), body, tag);
  }

  public static ChangeMessage newMessage(
      PatchSet.Id psId, CurrentUser user, Timestamp when, String body, @Nullable String tag) {
    checkNotNull(psId);
    Account.Id accountId = user.isInternalUser() ? null : user.getAccountId();
    ChangeMessage m =
        new ChangeMessage(
            new ChangeMessage.Key(psId.getParentKey(), ChangeUtil.messageUuid()),
            accountId,
            when,
            psId);
    m.setMessage(body);
    m.setTag(tag);
    user.updateRealAccountId(m::setRealAuthor);
    return m;
  }

  private static List<ChangeMessage> sortChangeMessages(Iterable<ChangeMessage> changeMessage) {
    return ChangeNotes.MESSAGE_BY_TIME.sortedCopy(changeMessage);
  }

  private final NotesMigration migration;

  @VisibleForTesting
  @Inject
  public ChangeMessagesUtil(NotesMigration migration) {
    this.migration = migration;
  }

  public List<ChangeMessage> byChange(ReviewDb db, ChangeNotes notes) throws OrmException {
    if (!migration.readChanges()) {
      return sortChangeMessages(db.changeMessages().byChange(notes.getChangeId()));
    }
    return notes.load().getChangeMessages();
  }

  public Iterable<ChangeMessage> byPatchSet(ReviewDb db, ChangeNotes notes, PatchSet.Id psId)
      throws OrmException {
    if (!migration.readChanges()) {
      return db.changeMessages().byPatchSet(psId);
    }
    return notes.load().getChangeMessagesByPatchSet().get(psId);
  }

  public void addChangeMessage(ReviewDb db, ChangeUpdate update, ChangeMessage changeMessage)
      throws OrmException {
    checkState(
        Objects.equals(changeMessage.getAuthor(), update.getNullableAccountId()),
        "cannot store change message by %s in update by %s",
        changeMessage.getAuthor(),
        update.getNullableAccountId());
    update.setChangeMessage(changeMessage.getMessage());
    update.setTag(changeMessage.getTag());
    db.changeMessages().insert(Collections.singleton(changeMessage));
  }
}
