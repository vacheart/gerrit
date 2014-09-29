// Copyright (C) 2010 The Android Open Source Project
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

package com.google.gerrit.server.config;

import com.google.gerrit.server.account.GroupBackend;
import com.google.gerrit.server.util.ServerRequestContext;
import com.google.gerrit.server.util.ThreadLocalRequestContext;

import org.eclipse.jgit.lib.Config;

import javax.inject.Inject;

/**
 * Provider of the group(s) which should become owners of a newly created
 * project. Currently only supports {@code ownerGroup} declarations in the
 * {@code "*"} repository, like so:
 *
 * <pre>
 * [repository &quot;*&quot;]
 *     ownerGroup = Registered Users
 *     ownerGroup = Administrators
 * </pre>
 */
public class ProjectOwnerGroupsProvider extends GroupSetProvider {
  @Inject
  public ProjectOwnerGroupsProvider(GroupBackend gb,
      @GerritServerConfig final Config config,
      ThreadLocalRequestContext context,
      ServerRequestContext serverCtx) {
    super(gb, config, context, serverCtx, "repository", "*", "ownerGroup");
  }
}
