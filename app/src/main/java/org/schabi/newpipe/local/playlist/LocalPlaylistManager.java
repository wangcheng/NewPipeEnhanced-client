package org.schabi.newpipe.local.playlist;

import androidx.annotation.Nullable;

import org.schabi.newpipe.database.AppDatabase;
import org.schabi.newpipe.database.playlist.PlaylistMetadataEntry;
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry;
import org.schabi.newpipe.database.playlist.dao.PlaylistDAO;
import org.schabi.newpipe.database.playlist.dao.PlaylistStreamDAO;
import org.schabi.newpipe.database.playlist.model.PlaylistEntity;
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity;
import org.schabi.newpipe.database.stream.dao.StreamDAO;
import org.schabi.newpipe.database.stream.model.StreamEntity;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class LocalPlaylistManager {
    private final AppDatabase database;
    private final StreamDAO streamTable;
    private final PlaylistDAO playlistTable;
    private final PlaylistStreamDAO playlistStreamTable;

    public LocalPlaylistManager(final AppDatabase db) {
        database = db;
        streamTable = db.streamDAO();
        playlistTable = db.playlistDAO();
        playlistStreamTable = db.playlistStreamDAO();
    }

    public Maybe<List<Long>> createPlaylist(final String name, final List<StreamEntity> streams) {
        // Disallow creation of empty playlists
        if (streams.isEmpty()) {
            return Maybe.empty();
        }
        final StreamEntity defaultStream = streams.get(0);

        // Save to the database directly.
        // Make sure the new playlist is always on the top of bookmark.
        // The index will be reassigned to non-negative number in BookmarkFragment.
        final PlaylistEntity newPlaylist =
                new PlaylistEntity(name, defaultStream.getThumbnailUrl(), -1);

        return Maybe.fromCallable(() -> database.runInTransaction(() ->
                upsertStreams(playlistTable.insert(newPlaylist), streams, 0))
        ).subscribeOn(Schedulers.io());
    }

    public Maybe<List<Long>> appendToPlaylist(final long playlistId,
                                              final List<StreamEntity> streams) {
        return playlistStreamTable.getMaximumIndexOf(playlistId)
                .firstElement()
                .map(maxJoinIndex -> database.runInTransaction(() ->
                        upsertStreams(playlistId, streams, maxJoinIndex + 1))
                ).subscribeOn(Schedulers.io());
    }

    private List<Long> upsertStreams(final long playlistId,
                                     final List<StreamEntity> streams,
                                     final int indexOffset) {

        final List<PlaylistStreamEntity> joinEntities = new ArrayList<>(streams.size());
        final List<Long> streamIds = streamTable.upsertAll(streams);
        for (int index = 0; index < streamIds.size(); index++) {
            joinEntities.add(new PlaylistStreamEntity(playlistId, streamIds.get(index),
                    index + indexOffset));
        }
        return playlistStreamTable.insertAll(joinEntities);
    }

    public Completable updateJoin(final long playlistId, final List<Long> streamIds) {
        final List<PlaylistStreamEntity> joinEntities = new ArrayList<>(streamIds.size());
        for (int i = 0; i < streamIds.size(); i++) {
            joinEntities.add(new PlaylistStreamEntity(playlistId, streamIds.get(i), i));
        }

        return Completable.fromRunnable(() -> database.runInTransaction(() -> {
            playlistStreamTable.deleteBatch(playlistId);
            playlistStreamTable.insertAll(joinEntities);
        })).subscribeOn(Schedulers.io());
    }

    public Completable updatePlaylists(final List<PlaylistMetadataEntry> updateItems,
                                       final List<Long> deletedItems) {
        final List<PlaylistEntity> items = new ArrayList<>(updateItems.size());
        for (final PlaylistMetadataEntry item : updateItems) {
            items.add(new PlaylistEntity(item));
        }
        return Completable.fromRunnable(() -> database.runInTransaction(() -> {
            for (final Long uid: deletedItems) {
                playlistTable.deletePlaylist(uid);
            }
            for (final PlaylistEntity item: items) {
                playlistTable.upsertPlaylist(item);
            }
        })).subscribeOn(Schedulers.io());
    }

    public Flowable<List<PlaylistMetadataEntry>> getPlaylists() {
        return playlistStreamTable.getPlaylistMetadata().subscribeOn(Schedulers.io());
    }

    public Flowable<List<PlaylistMetadataEntry>> getDisplayIndexOrderedPlaylists() {
        return playlistStreamTable.getDisplayIndexOrderedPlaylistMetadata()
                .subscribeOn(Schedulers.io());
    }

    public Flowable<List<PlaylistStreamEntry>> getPlaylistStreams(final long playlistId) {
        return playlistStreamTable.getOrderedStreamsOf(playlistId).subscribeOn(Schedulers.io());
    }

    public Single<Integer> deletePlaylist(final long playlistId) {
        return Single.fromCallable(() -> playlistTable.deletePlaylist(playlistId))
                .subscribeOn(Schedulers.io());
    }

    public Maybe<Integer> renamePlaylist(final long playlistId, final String name) {
        return modifyPlaylist(playlistId, name, null, -1);
    }

    public Maybe<Integer> changePlaylistThumbnail(final long playlistId,
                                                  final String thumbnailUrl) {
        return modifyPlaylist(playlistId, null, thumbnailUrl, -1);
    }

    public Maybe<Integer> updatePlaylistDisplayIndex(final long playlistId,
                                                     final long displayIndex) {
        return modifyPlaylist(playlistId, null, null, displayIndex);
    }

    public String getPlaylistThumbnail(final long playlistId) {
        return playlistTable.getPlaylist(playlistId).blockingFirst().get(0).getThumbnailUrl();
    }

    private Maybe<Integer> modifyPlaylist(final long playlistId,
                                          @Nullable final String name,
                                          @Nullable final String thumbnailUrl,
                                          final long displayIndex) {
        return playlistTable.getPlaylist(playlistId)
                .firstElement()
                .filter(playlistEntities -> !playlistEntities.isEmpty())
                .map(playlistEntities -> {
                    final PlaylistEntity playlist = playlistEntities.get(0);
                    if (name != null) {
                        playlist.setName(name);
                    }
                    if (thumbnailUrl != null) {
                        playlist.setThumbnailUrl(thumbnailUrl);
                    }
                    if (displayIndex != -1) {
                        playlist.setDisplayIndex(displayIndex);
                    }
                    return playlistTable.update(playlist);
                }).subscribeOn(Schedulers.io());
    }

    public Maybe<Boolean> hasPlaylists() {
        return playlistTable.getCount()
                .firstElement()
                .map(count -> count > 0)
                .subscribeOn(Schedulers.io());
    }
}
