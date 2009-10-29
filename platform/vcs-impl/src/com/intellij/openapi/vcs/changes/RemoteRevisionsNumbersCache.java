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
package com.intellij.openapi.vcs.changes;

import com.intellij.lifecycle.AtomicSectionsAware;
import com.intellij.lifecycle.ControlledAlarmFactory;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.FilePathImpl;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsRoot;
import com.intellij.openapi.vcs.diff.DiffProvider;
import com.intellij.openapi.vcs.diff.ItemLatestState;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;

/**
 * for vcses where it is reasonable to ask revision of each item separately
 */
public class RemoteRevisionsNumbersCache implements ChangesOnServerTracker {
  public static final Logger LOG = Logger.getInstance("#com.intellij.openapi.vcs.changes.RemoteRevisionsNumbersCache");
  
  // every 5 minutes.. (time unit to check for server commits)
  private static final long ourRottenPeriod = 300 * 1000;
  private final Map<String, Pair<VcsRoot, VcsRevisionNumber>> myData;
  private final Map<VcsRoot, LazyRefreshingSelfQueue<String>> myRefreshingQueues;
  private final Map<String, VcsRevisionNumber> myLatestRevisionsMap;
  private final ProjectLevelVcsManager myVcsManager;
  private final LocalFileSystem myLfs;
  private boolean mySomethingChanged;

  private final Object myLock;

  public static final VcsRevisionNumber NOT_LOADED = new VcsRevisionNumber() {
    public String asString() {
      return "NOT_LOADED";
    }

    public int compareTo(VcsRevisionNumber o) {
      if (o == this) return 0;
      return -1;
    }
  };
  public static final VcsRevisionNumber UNKNOWN = new VcsRevisionNumber() {
    public String asString() {
      return "UNKNOWN";
    }

    public int compareTo(VcsRevisionNumber o) {
      if (o == this) return 0;
      return -1;
    }
  };

  RemoteRevisionsNumbersCache(final Project project) {
    myLock = new Object();
    myData = new HashMap<String, Pair<VcsRoot, VcsRevisionNumber>>();
    myRefreshingQueues = Collections.synchronizedMap(new HashMap<VcsRoot, LazyRefreshingSelfQueue<String>>());
    myLatestRevisionsMap = new HashMap<String, VcsRevisionNumber>();
    myLfs = LocalFileSystem.getInstance();
    myVcsManager = ProjectLevelVcsManager.getInstance(project);
  }

  public boolean updateStep(final AtomicSectionsAware atomicSectionsAware) {
    final List<LazyRefreshingSelfQueue<String>> list = new ArrayList<LazyRefreshingSelfQueue<String>>();
    mySomethingChanged = false;
    synchronized (myLock) {
      list.addAll(myRefreshingQueues.values());
    }
    LOG.debug("queues refresh started, queues: " + list.size());
    final ProgressIndicator pi = ControlledAlarmFactory.createProgressIndicator(atomicSectionsAware);
    for (LazyRefreshingSelfQueue<String> queue : list) {
      atomicSectionsAware.checkShouldExit();
      queue.updateStep(pi);
    }
    return mySomethingChanged;
  }

  public void directoryMappingChanged() {
    synchronized (myLock) {
      final HashSet<String> keys = new HashSet<String>(myData.keySet());
      for (String key : keys) {
        final Pair<VcsRoot, VcsRevisionNumber> value = myData.get(key);
        final VcsRoot storedVcsRoot = value.getFirst();
        final VirtualFile vf = myLfs.refreshAndFindFileByIoFile(new File(key));
        final AbstractVcs newVcs = (vf == null) ? null : myVcsManager.getVcsFor(vf);

        if (newVcs == null) {
          myData.remove(key);
          getQueue(storedVcsRoot).forceRemove(key);
        } else {
          final VirtualFile newRoot = myVcsManager.getVcsRootFor(vf);
          final VcsRoot newVcsRoot = new VcsRoot(newVcs, newRoot);
          if (! storedVcsRoot.equals(newVcsRoot)) {
            switchVcs(storedVcsRoot, newVcsRoot, key);
          }
        }
      }
    }
  }

  private void switchVcs(final VcsRoot oldVcsRoot, final VcsRoot newVcsRoot, final String key) {
    synchronized (myLock) {
      final LazyRefreshingSelfQueue<String> oldQueue = getQueue(oldVcsRoot);
      final LazyRefreshingSelfQueue<String> newQueue = getQueue(newVcsRoot);
      myData.put(key, new Pair<VcsRoot, VcsRevisionNumber>(newVcsRoot, NOT_LOADED));
      oldQueue.forceRemove(key);
      newQueue.addRequest(key);
    }
  }

  public void plus(final Pair<String, AbstractVcs> pair) {
    // does not support
    if (pair.getSecond().getDiffProvider() == null) return;

    final String key = pair.getFirst();
    final AbstractVcs newVcs = pair.getSecond();

    final VirtualFile root = getRootForPath(key);
    if (root == null) return;

    final VcsRoot vcsRoot = new VcsRoot(newVcs, root);

    synchronized (myLock) {
      final Pair<VcsRoot, VcsRevisionNumber> value = myData.get(key);
      if (value == null) {
        final LazyRefreshingSelfQueue<String> queue = getQueue(vcsRoot);
        myData.put(key, new Pair<VcsRoot, VcsRevisionNumber>(vcsRoot, NOT_LOADED));
        queue.addRequest(key);
      } else if (! value.getFirst().equals(vcsRoot)) {
        switchVcs(value.getFirst(), vcsRoot, key);
      }
    }
  }

  public void invalidate(final Collection<String> paths) {
    synchronized (myLock) {
      for (String path : paths) {
        final Pair<VcsRoot, VcsRevisionNumber> pair = myData.remove(path);
        if (pair != null) {
          // vcs [root] seems to not change
          final VcsRoot vcsRoot = pair.getFirst();
          final LazyRefreshingSelfQueue<String> queue = getQueue(vcsRoot);
          queue.forceRemove(path);
          queue.addRequest(path);
          myData.put(path, new Pair<VcsRoot, VcsRevisionNumber>(vcsRoot, NOT_LOADED));
        }
      }
    }
  }

  @Nullable
  private VirtualFile getRootForPath(final String s) {
    return myVcsManager.getVcsRootFor(new FilePathImpl(new File(s), false));
  }

  public void minus(Pair<String, AbstractVcs> pair) {
    // does not support
    if (pair.getSecond().getDiffProvider() == null) return;
    final VirtualFile root = getRootForPath(pair.getFirst());
    if (root == null) return;

    final LazyRefreshingSelfQueue<String> queue;
    final String key = pair.getFirst();
    synchronized (myLock) {
      queue = getQueue(new VcsRoot(pair.getSecond(), root));
      myData.remove(key);
    }
    queue.forceRemove(key);
  }

  // +-
  @NotNull
  private LazyRefreshingSelfQueue<String> getQueue(final VcsRoot vcsRoot) {
    synchronized (myLock) {
      LazyRefreshingSelfQueue<String> queue = myRefreshingQueues.get(vcsRoot);
      if (queue != null) return queue;

      queue = new LazyRefreshingSelfQueue<String>(ourRottenPeriod, new MyShouldUpdateChecker(vcsRoot), new MyUpdater(vcsRoot));
      myRefreshingQueues.put(vcsRoot, queue);
      return queue;
    }
  }

  private class MyUpdater implements Consumer<String> {
    private final VcsRoot myVcsRoot;

    public MyUpdater(final VcsRoot vcsRoot) {
      myVcsRoot = vcsRoot;
    }

    public void consume(String s) {
      LOG.debug("update for: " + s);
      //todo check canceled
      final VirtualFile vf = myLfs.refreshAndFindFileByIoFile(new File(s));
      final ItemLatestState state;
      final DiffProvider diffProvider = myVcsRoot.vcs.getDiffProvider();
      if (vf == null) {
        // doesnt matter if directory or not
        state = diffProvider.getLastRevision(FilePathImpl.createForDeletedFile(new File(s), false));
      } else {
        state = diffProvider.getLastRevision(vf);
      }
      final VcsRevisionNumber newNumber = state == null ? UNKNOWN : state.getNumber();

      final Pair<VcsRoot, VcsRevisionNumber> oldPair;
      synchronized (myLock) {
        oldPair = myData.get(s);
        myData.put(s, new Pair<VcsRoot, VcsRevisionNumber>(myVcsRoot, newNumber));
      }

      if ((oldPair == null) || (oldPair != null) && (oldPair.getSecond().compareTo(newNumber) != 0)) {
        LOG.debug("refresh triggered by " + s);
        mySomethingChanged = true;
      }
    }
  }

  private class MyShouldUpdateChecker implements Computable<Boolean> {
    private final VcsRoot myVcsRoot;

    public MyShouldUpdateChecker(final VcsRoot vcsRoot) {
      myVcsRoot = vcsRoot;
    }

    public Boolean compute() {
      final AbstractVcs vcs = myVcsRoot.vcs;
      // won't be called in parallel for same vcs -> just synchronized map is ok
      final String vcsName = vcs.getName();
      LOG.debug("should update for: " + vcsName + " root: " + myVcsRoot.path.getPath());
      final VcsRevisionNumber latestNew = vcs.getDiffProvider().getLatestCommittedRevision(myVcsRoot.path);

      final VcsRevisionNumber latestKnown = myLatestRevisionsMap.get(vcsName);
      // not known
      if (latestNew == null) return true;
      if ((latestKnown == null) || (latestNew.compareTo(latestKnown) != 0)) {
        myLatestRevisionsMap.put(vcsName, latestNew);
        return true;
      }
      return false;
    }
  }

  private VcsRevisionNumber getNumber(final String path) {
    synchronized (myLock) {
      final Pair<VcsRoot, VcsRevisionNumber> pair = myData.get(path);
      return pair == null ? NOT_LOADED : pair.getSecond();
    }
  }

  public boolean isUpToDate(final Change change) {
    if (change.getBeforeRevision() != null && change.getAfterRevision() != null && (! change.isMoved()) && (! change.isRenamed())) {
      return getRevisionState(change.getBeforeRevision());
    }
    return getRevisionState(change.getBeforeRevision()) && getRevisionState(change.getAfterRevision());
  }

  private boolean getRevisionState(final ContentRevision revision) {
    if (revision != null) {
      final VcsRevisionNumber local = revision.getRevisionNumber();
      final String path = revision.getFile().getIOFile().getAbsolutePath();
      final VcsRevisionNumber remote = getNumber(path);
      if ((NOT_LOADED == remote) || (UNKNOWN == remote)) {
        return true;
      }
      return local.compareTo(remote) == 0;
    }
    return true;
  }
}
