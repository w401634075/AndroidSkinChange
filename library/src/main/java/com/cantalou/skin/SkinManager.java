package com.cantalou.skin;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.LayoutInflater.Factory;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.Window;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.ImageView;

import com.cantalou.android.manager.lifecycle.ActivityLifecycleCallbacksAdapter;
import com.cantalou.android.manager.lifecycle.ActivityLifecycleManager;
import com.cantalou.android.util.Log;
import com.cantalou.android.util.PrefUtil;
import com.cantalou.android.util.ReflectUtil;
import com.cantalou.android.util.StringUtils;
import com.cantalou.skin.content.SkinContextWrapper;
import com.cantalou.skin.factory.ViewFactory;
import com.cantalou.skin.factory.ViewFactoryAfterGingerbread;
import com.cantalou.skin.handler.AbstractHandler;
import com.cantalou.skin.handler.ViewHandler;
import com.cantalou.skin.manager.ResourcesManager;
import com.cantalou.skin.manager.ResourcesManagerFactory;
import com.cantalou.skin.manager.hook.HookPreloadResourcesManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 皮肤资源Manager
 *
 * @author cantalou
 * @date 2015年10月31日 下午3:49:46
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
public class SkinManager extends ActivityLifecycleCallbacksAdapter {

    /**
     * 当前皮肤存储key
     */
    public static final String PREF_KEY_CURRENT_SKIN = "com.cantalou.skin.PREF_KEY_CURRENT_SKIN";

    /**
     * activity
     */
    ArrayList<Activity> activities = new ArrayList<Activity>();

    /**
     * 当前是否正在切换资源
     */
    volatile int changingResourceToken;

    /**
     * 资源名称
     */
    String currentSkinPath = HookPreloadResourcesManager.DEFAULT_RESOURCES;

    /**
     * 资源
     */
    private Resources currentResources;

    /**
     * 资源切换时提交View刷新任务到UI线程
     */
    private Handler uiHandler = new Handler(Looper.getMainLooper());

    /**
     * 资源切换结束回调
     */
    private ArrayList<OnResourcesChangeFinishListener> onResourcesChangeFinishListeners = new ArrayList<OnResourcesChangeFinishListener>();

    private ResourcesManager resourcesManager;

    private ActivityLifecycleManager activityLifecycleManager;

    private Context context;

    /**
     * 串行执行ui更新任务
     */
    ArrayDeque<Runnable> uiSerialTasks = new ArrayDeque<Runnable>() {
        Runnable mActive;

        public synchronized boolean offer(final Runnable e) {
            boolean result = super.offer(new Runnable() {
                @Override
                public void run() {
                    try {
                        long start = System.currentTimeMillis();
                        e.run();
                        Log.i("onResourcesChange consume time :{} ", System.currentTimeMillis() - start);
                    } finally {
                        scheduleNext();
                    }
                }
            });
            if (mActive == null) {
                scheduleNext();
            }
            return result;
        }

        public synchronized void scheduleNext() {
            mActive = uiSerialTasks.poll();
            if (mActive != null) {
                uiHandler.post(mActive);
            }
        }
    };


    private SkinManager() {
    }

    public void init(Context context, ResourcesManagerFactory factory) {

        this.context = context.getApplicationContext();

        activityLifecycleManager = ActivityLifecycleManager.getInstance();
        activityLifecycleManager.registerActivityLifecycleCallbacks(this);

        if (factory == null) {
            factory = new ResourcesManagerFactory();
        }
        resourcesManager = factory.createResourcesManager(context);

        Log.LOG_TAG_FLAG = "-skin";
    }

    private static class InstanceHolder {
        static final SkinManager INSTANCE = new SkinManager();
    }

    public static SkinManager getInstance() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * 注册自定义的ViewFactory到LayoutInflater中,实现对View生成的拦截
     *
     * @param li
     */
    public void registerViewFactory(LayoutInflater li) {
        Factory factory = li.getFactory();
        if (factory instanceof ViewFactory) {
            Log.w("Had register factory");
            return;
        }

        if (factory != null && ReflectUtil.get(factory, "mF1") instanceof ViewFactory) {
            Log.w("Had register factory");
            return;
        }

        ViewFactory vf;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
            vf = new ViewFactory();
        } else {
            vf = new ViewFactoryAfterGingerbread();
        }
        vf.register(li);
        Log.d("LayoutInflater:{} register custom factory:{}", li, vf);
    }

    /**
     * 替换所有Activity的Resource为指定路径的资源
     *
     * @param activity
     * @param path     资源文件路径
     */
    @SuppressWarnings("unchecked")
    public void changeResources(final Activity activity, final String path) {

        if (StringUtils.isBlank(path)) {
            throw new IllegalArgumentException("skinPath could not be empty");
        }

        showSkinChangeAnimation(activity);
        final Context cxt = activity.getApplicationContext();
        changingResourceToken = activity.hashCode();
        new AsyncTask<Void, Void, Boolean>() {

            @Override
            protected Boolean doInBackground(Void... params) {
                Resources originalResources = currentResources;
                try {
                    Log.d("start change resource");
                    final Resources res = resourcesManager.createResources(cxt, path);
                    if (res == null) {
                        return false;
                    }
                    currentResources = res;
                    List<Activity> temp = (List<Activity>) activities.clone();
                    for (int i = temp.size() - 1; i >= 0; i--) {
                        Log.d("change :{} resources to :{}", temp.get(i), res);
                        change(temp.get(i), res);
                    }
                    Log.d("finish change resource");
                    currentSkinPath = path;
                    PrefUtil.setString(cxt, PREF_KEY_CURRENT_SKIN, path);
                    return true;
                } catch (Exception e) {
                    Log.e(e);
                    currentResources = originalResources;
                    return false;
                }
            }

            @Override
            protected void onPostExecute(Boolean result) {
                Log.i("changeResources doInBackground return :{}, currentSkin:{}", result, currentSkinPath);
                ArrayList<OnResourcesChangeFinishListener> list = (ArrayList<OnResourcesChangeFinishListener>) onResourcesChangeFinishListeners.clone();
                for (OnResourcesChangeFinishListener listener : list) {
                    listener.onResourcesChangeFinish(result);
                }
                changingResourceToken = 0;
            }
        }.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR);


    }

    /**
     * 更换activity的资源, 调用OnResourcesChangeListener回调进行自定义资源的更新
     *
     * @param a   activity
     * @param res 资源
     */
    protected void change(final Activity a, Resources res) {

        changeActivityResources(a, res);

        if (changingResourceToken == a.hashCode()) {

            if (a instanceof OnResourcesChangeFinishListener) {
                uiSerialTasks.offer(new Runnable() {
                    @Override
                    public void run() {
                        ((OnResourcesChangeFinishListener) a).onResourcesChangeFinish(true);
                    }
                });
            }

            Object fragmentManager = ReflectUtil.get(a, "mFragments");
            if (fragmentManager != null) {
                List<?> fragments = ReflectUtil.get(fragmentManager, "mAdded");
                if (fragments == null) {
                    fragmentManager = ReflectUtil.invoke(fragmentManager, "getFragmentManager");
                    if (fragmentManager == null) {
                        fragmentManager = ReflectUtil.invoke(fragmentManager, "getSupportFragmentManager");
                    }
                    fragments = ReflectUtil.get(fragmentManager, "mAdded");
                }

                if (fragments != null && fragments.size() > 0) {
                    for (final Object f : fragments) {
                        if (f instanceof OnResourcesChangeFinishListener) {
                            uiSerialTasks.offer(new Runnable() {
                                @Override
                                public void run() {
                                    ((OnResourcesChangeFinishListener) f).onResourcesChangeFinish(true);
                                }
                            });
                        }
                    }
                }
            }

            final Window w = a.getWindow();
            if (w != null) {
                uiSerialTasks.offer(new Runnable() {
                    @Override
                    public void run() {
                        onResourcesChange(w.getDecorView());
                    }
                });
            }
        } else {
            uiSerialTasks.offer(new Runnable() {
                @Override
                public void run() {
                    a.recreate();
                }
            });
        }


    }

    /**
     * 将Activity资源替换成toRes指定资源
     *
     * @param activity 触发切换资源的Activity
     * @param toRes    新资源
     */
    public void changeActivityResources(Activity activity, Resources toRes) {
        // ContextThemeWrapper add mResources field in JELLY_BEAN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            Log.v("after JELLY_BEAN change Activity:{} to Resources :{} ,result:{} ", activity, toRes, ReflectUtil.set(activity, "mResources", toRes));
        } else {
            Log.v("before JELLY_BEAN change context:{} to Resources :{} ,result:{} ", activity.getBaseContext(), toRes, ReflectUtil.set(activity.getBaseContext(), "mResources", toRes));
        }
        Log.v("reset theme to null ", ReflectUtil.set(activity, "mTheme", null));
        activity.getTheme();
    }

    /**
     * 1.递归调用实现了OnResourcesChangeListener接口的View
     * 2.调用对应的ViewHandler进行View资源的重新加载
     *
     * @param v
     */
    public void onResourcesChange(View v) {

        if (v == null) {
            return;
        }

        if (v instanceof OnResourcesChangeFinishListener) {
            ((OnResourcesChangeFinishListener) v).onResourcesChangeFinish(true);
        }

        Object tag = v.getTag(ViewHandler.ATTR_HANDLER_KEY);
        if (tag != null && tag instanceof ViewHandler) {
            ((AbstractHandler) tag).reload(v, currentResources, false);
        } else {
            AbstractHandler ah = ViewFactory.getHandler(v.getClass().getName());
            if (ah != null) {
                // ah.reload(v, currentResources, false);
            }
        }

        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            int size = vg.getChildCount();
            for (int i = 0; i < size; i++) {
                onResourcesChange(vg.getChildAt(i));
            }
        }
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        activities.remove(activity);
    }

    /**
     * 注册需要换肤的Activity
     *
     * @param activity
     * @param savedInstanceState
     */
    @Override
    public void beforeActivityOnCreate(Activity activity, Bundle savedInstanceState) {

        activities.add(activity);

        Context baseContext = activity.getBaseContext();
        if (!(baseContext instanceof SkinContextWrapper)) {
            ReflectUtil.set(activity, "mBase", new SkinContextWrapper(baseContext));
            Log.v("replace Activity baseContext to :{} ", baseContext);
        }

        LayoutInflater li = activity.getLayoutInflater();
        registerViewFactory(li);

        String prefSkinPath = PrefUtil.getString(activity, PREF_KEY_CURRENT_SKIN);
        if (StringUtils.isNotBlank(prefSkinPath)) {
            currentSkinPath = prefSkinPath;
        }

        Resources res = resourcesManager.createResources(activity, currentSkinPath);
        try {
            changeActivityResources(activity, res);
            currentResources = res;
        } catch (Exception e) {
            Log.e(e);
        }
    }

    /**
     * 对当前界面截图, 模糊渐变消失
     *
     * @param activity 要显示渐变动画的界面
     */
    private void showSkinChangeAnimation(final Activity activity) {
        try {

            final ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            if (decor == null) {
                return;
            }

            decor.setDrawingCacheEnabled(true);
            Bitmap src = decor.getDrawingCache();
            Bitmap temp = Bitmap.createBitmap(src);
            decor.setDrawingCacheEnabled(false);

            final ImageView iv = new ImageView(activity);
            iv.setImageBitmap(temp);
            iv.setFocusable(true);
            iv.setFocusableInTouchMode(true);
            iv.requestFocus();
            iv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // consume all event
                }
            });
            iv.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    // consume all event
                    return true;
                }
            });

            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
            decor.addView(iv, lp);

            AlphaAnimation aa = new AlphaAnimation(1F, 0F);
            aa.setDuration(800);
            aa.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {
                }

                @Override
                public void onAnimationEnd(Animation animation) {
                    decor.removeView(iv);
                }

                @Override
                public void onAnimationRepeat(Animation animation) {
                }
            });
            iv.setAnimation(aa);
            aa.start();

        } catch (Throwable e) {
            Log.e(e);
        }
    }

    /**
     * 当前是否正在切换资源
     *
     * @return 是 true
     */
    public boolean isChangingResource() {
        return changingResourceToken != 0;
    }

    public String getCurrentSkin() {
        return currentSkinPath;
    }

    public synchronized void addOnResourcesChangeFinishListener(OnResourcesChangeFinishListener listener) {
        onResourcesChangeFinishListeners.add(listener);
    }

    public synchronized void removeOnResourcesChangeFinishListener(OnResourcesChangeFinishListener listener) {
        onResourcesChangeFinishListeners.remove(listener);
    }

    public Resources getCurrentResources() {
        return currentResources;
    }

    public void setCurrentResources(Resources currentResources) {
        this.currentResources = currentResources;
    }

    public ArrayList<Activity> getActivities() {
        return activities;
    }

}
