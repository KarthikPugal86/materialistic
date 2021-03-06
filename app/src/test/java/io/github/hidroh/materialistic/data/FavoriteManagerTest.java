package io.github.hidroh.materialistic.data;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.os.Parcel;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.content.ShadowContentResolverCompatJellybean;
import android.support.v7.app.AppCompatActivity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import io.github.hidroh.materialistic.test.RobolectricGradleTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowNetworkInfo;
import org.robolectric.shadows.support.v4.ShadowLocalBroadcastManager;
import org.robolectric.util.ActivityController;

import java.util.HashSet;
import java.util.Set;

import io.github.hidroh.materialistic.Application;
import io.github.hidroh.materialistic.R;
import io.github.hidroh.materialistic.test.shadow.ShadowSupportPreferenceManager;
import io.github.hidroh.materialistic.test.shadow.ShadowWebView;
import io.github.hidroh.materialistic.test.TestWebItem;
import rx.schedulers.Schedulers;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;
import static org.assertj.android.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.robolectric.shadows.support.v4.Shadows.shadowOf;

@Config(shadows = {ShadowContentResolverCompatJellybean.class, ShadowWebView.class, ShadowSupportPreferenceManager.class})
@RunWith(RobolectricGradleTestRunner.class)
public class FavoriteManagerTest {
    private ShadowContentResolver resolver;
    private FavoriteManager manager;

    @Before
    public void setUp() {
        resolver = shadowOf(RuntimeEnvironment.application.getContentResolver());
        ContentValues cv = new ContentValues();
        cv.put("itemid", "1");
        cv.put("title", "title");
        cv.put("url", "http://example.com");
        cv.put("time", String.valueOf(System.currentTimeMillis()));
        resolver.insert(MaterialisticProvider.URI_FAVORITE, cv);
        cv = new ContentValues();
        cv.put("itemid", "2");
        cv.put("title", "ask HN");
        cv.put("url", "http://example.com");
        cv.put("time", String.valueOf(System.currentTimeMillis()));
        resolver.insert(MaterialisticProvider.URI_FAVORITE, cv);
        manager = new FavoriteManager(Schedulers.immediate());
    }

    @Test
    public void testLocalItemManager() {
        ActivityController<AppCompatActivity> controller = Robolectric.buildActivity(AppCompatActivity.class);
        AppCompatActivity activity = controller.create().start().resume().get();
        LocalItemManager.Observer observer = mock(LocalItemManager.Observer.class);
        manager.attach(RuntimeEnvironment.application, activity.getSupportLoaderManager(),
                observer, null);
        verify(observer).onChanged();
        assertThat(manager.getSize()).isEqualTo(2);
        assertThat(manager.getItem(0).getDisplayedTitle()).contains("ask HN");
        assertThat(manager.getItem(1).getDisplayedTitle()).contains("title");
        manager.detach();
        controller.pause().stop().destroy();
    }

    @Test
    public void testGetNoQuery() {
        manager.get(RuntimeEnvironment.application, null);
        Intent actual = getBroadcastIntent();
        assertThat(actual).hasAction(FavoriteManager.ACTION_GET);
        assertThat(actual.getParcelableArrayListExtra(FavoriteManager.ACTION_GET_EXTRA_DATA))
                .hasSize(2);
    }

    @Test
    public void testGetEmpty() {
        manager.get(RuntimeEnvironment.application, "blah");
        Intent actual = getBroadcastIntent();
        assertThat(actual).hasAction(FavoriteManager.ACTION_GET);
        assertThat(actual.getParcelableArrayListExtra(FavoriteManager.ACTION_GET_EXTRA_DATA))
                .isEmpty();
    }

    @Test
    public void testCheckNoId() {
        assertFalse(manager.check(RuntimeEnvironment.application.getContentResolver(), null)
                .toBlocking().single());
    }

    @Test
    public void testCheckTrue() {
        assertTrue(manager.check(RuntimeEnvironment.application.getContentResolver(), "1")
                .toBlocking().single());
    }

    @Test
    public void testCheckFalse() {
        assertFalse(manager.check(RuntimeEnvironment.application.getContentResolver(), "-1")
                .toBlocking().single());
    }

    @Test
    public void testAdd() {
        ShadowSupportPreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.application)
                .edit()
                .putBoolean(RuntimeEnvironment.application
                        .getString(R.string.pref_saved_item_sync), true)
                .putBoolean(RuntimeEnvironment.application
                        .getString(R.string.pref_offline_article), true)
                .commit();
        shadowOf((ConnectivityManager) RuntimeEnvironment.application
                .getSystemService(Context.CONNECTIVITY_SERVICE))
                .setActiveNetworkInfo(ShadowNetworkInfo.newInstance(null,
                        ConnectivityManager.TYPE_WIFI, 0, true, true));
        manager.add(RuntimeEnvironment.application, new TestWebItem() {
            @Override
            public String getId() {
                return "3";
            }

            @Override
            public String getUrl() {
                return "http://newitem.com";
            }

            @Override
            public String getDisplayedTitle() {
                return "new title";
            }
        });
        assertThat(resolver.getNotifiedUris()).isNotEmpty();
        assertTrue(ShadowContentResolver.isSyncActive(Application.createSyncAccount(),
                MaterialisticProvider.PROVIDER_AUTHORITY));
    }

    @Test
    public void testReAdd() {
        Favorite favorite = mock(Favorite.class);
        when(favorite.getId()).thenReturn("3");
        when(favorite.getUrl()).thenReturn("http://example.com");
        when(favorite.getDisplayedTitle()).thenReturn("title");
        manager.add(RuntimeEnvironment.application, favorite);
        assertThat(resolver.getNotifiedUris()).isNotEmpty();
    }

    @Test
    public void testClearAll() {
        manager.clear(RuntimeEnvironment.application, null);
        assertThat(resolver.getNotifiedUris()).isNotEmpty();
    }

    @Test
    public void testClearQuery() {
        manager.clear(RuntimeEnvironment.application, "blah");
        assertThat(resolver.getNotifiedUris()).isNotEmpty();
    }

    @Test
    public void testRemoveNoId() {
        manager.remove(RuntimeEnvironment.application, (String) null);
        assertThat(shadowOf(LocalBroadcastManager.getInstance(RuntimeEnvironment.application))
                .getSentBroadcastIntents()).isEmpty();
    }

    @Test
    public void testRemoveId() {
        manager.remove(RuntimeEnvironment.application, "1");
        assertThat(resolver.getNotifiedUris()).isNotEmpty();
    }

    @Test
    public void testRemoveMultipleNoId() {
        manager.remove(RuntimeEnvironment.application, (Set<String>) null);
        assertThat(resolver.getNotifiedUris()).isEmpty();
    }

    @Test
    public void testRemoveMultiple() {
        manager.remove(RuntimeEnvironment.application, new HashSet<String>(){{add("1");add("2");}});
        assertThat(resolver.getNotifiedUris()).isNotEmpty();
    }

    @Test
    public void testFavorite() {
        Parcel parcel = Parcel.obtain();
        parcel.writeString("1");
        parcel.writeString("http://example.com");
        parcel.writeString("title");
        parcel.setDataPosition(0);
        Favorite favorite = Favorite.CREATOR.createFromParcel(parcel);
        assertEquals("title", favorite.getDisplayedTitle());
        assertEquals("example.com", favorite.getSource());
        assertEquals("http://example.com", favorite.getUrl());
        assertEquals("1", favorite.getId());
        assertNotNull(favorite.getDisplayedAuthor(RuntimeEnvironment.application, true, 0));
        assertEquals(Item.STORY_TYPE, favorite.getType());
        assertTrue(favorite.isStoryType());
        assertEquals("title (http://example.com) - https://news.ycombinator.com/item?id=1", favorite.toString());
        assertEquals(0, favorite.describeContents());
        Parcel output = Parcel.obtain();
        favorite.writeToParcel(output, 0);
        output.setDataPosition(0);
        assertEquals("1", output.readString());
        assertThat(Favorite.CREATOR.newArray(1)).hasSize(1);
    }

    private Intent getBroadcastIntent() {
        ShadowLocalBroadcastManager broadcastManager = shadowOf(LocalBroadcastManager.getInstance(RuntimeEnvironment.application));
        return broadcastManager.getSentBroadcastIntents().get(0);
    }
}
