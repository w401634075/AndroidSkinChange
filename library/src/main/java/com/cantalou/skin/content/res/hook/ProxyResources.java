package com.cantalou.skin.content.res.hook;

import android.content.res.AssetManager;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.TypedValue;

import com.cantalou.android.skin.BuildConfig;
import com.cantalou.android.util.Log;
import com.cantalou.android.util.ReflectUtil;
import com.cantalou.skin.manager.hook.ResourcesCacheKeyIdManager;
import com.cantalou.skin.SkinManager;

import java.io.InputStream;

import static android.os.Build.VERSION.SDK_INT;
import static com.cantalou.android.util.ReflectUtil.invoke;

/**
 * 代理获取资源<p>
 * 1.实现loadDrawable(int id)和loadColorStateList(int id)自定义加载资源<p>
 *
 * @author cantalou
 * @date 2015-12-12 下午11:07:07
 */
public class ProxyResources extends Resources {

    /**
     * app资源id前缀
     */
    public static final int APP_ID_MASK = 0x7F000000;

    /**
     * 资源名称缓存数量
     */
    public static final int RESOURCE_NAME_CACHE_SIZE = 31;

    /**
     * 资源名称缓存id
     */
    protected int[] resourceNameIdCache = new int[RESOURCE_NAME_CACHE_SIZE + 1];

    /**
     * 资源名称缓存, 减少短时间内重复调用getResourceName的次数
     */
    protected String[] resourceNameCache = new String[RESOURCE_NAME_CACHE_SIZE + 1];

    protected static final Class<?>[] loadXmlResourceParserParam = new Class[]{String.class, int.class, int.class, String.class};

    protected static final Class<?>[] openNonAssetParam = new Class[]{int.class, String.class, int.class};

    protected final TypedValue typedValueCache = new TypedValue();

    /**
     * 用于标记当前正在加载资源
     */
    public static final ThreadLocal<Resources> loadingFlag = new ThreadLocal<Resources>();

    public ProxyResources(Resources res) {
        super(res.getAssets(), res.getDisplayMetrics(), res.getConfiguration());
    }

    @Override
    public XmlResourceParser getLayout(int id) throws NotFoundException {
        return super.getLayout(id);
    }

    protected String toString(TypedValue value) {
        StringBuilder sb = new StringBuilder();
        sb.append("TypedValue{t=0x").append(Integer.toHexString(value.type));
        sb.append("/d=0x").append(Integer.toHexString(value.data));
        if (value.type == TypedValue.TYPE_STRING) {
            sb.append(" \"").append(value.string != null ? value.string : getResourceName(value.resourceId)).append("\"");
        }
        if (value.assetCookie != 0) {
            sb.append(" a=").append(value.assetCookie);
        }
        if (value.resourceId != 0) {
            sb.append(" r=0x").append(Integer.toHexString(value.resourceId));
        }
        sb.append("}");
        return sb.toString();
    }

    public static final String toHex(int id) {
        return "0x" + Integer.toHexString(id);
    }

    @Override
    public String getResourceName(int resId) throws NotFoundException {

        if (resId == 0) {
            return "";
        }

        int index = resId & RESOURCE_NAME_CACHE_SIZE;
        String name = null;
        if (resourceNameIdCache[index] == resId) {
            name = resourceNameCache[index];
        }

        if (name == null || resourceNameIdCache[index] != resId) {
            try {
                name = super.getResourceName(resId);
                resourceNameIdCache[index] = resId;
                resourceNameCache[index] = name;
            } catch (Exception e) {
            }
        }
        return name;
    }

    public static final boolean isColor(TypedValue value) {
        return value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT;
    }

    /**
     * 尝试使用 loadDrawable(Resources, TypedValue, int)方法进行自定义的资源加载, 失败时再调用getDrawable(int)加载
     *
     * @param id 资源id
     * @return 资源
     * @throws NotFoundException
     */
    public Drawable loadDrawable(int id) throws NotFoundException {
        TypedValue value = typedValueCache;
        getValue(id, value, true);
        Drawable dr = loadDrawable(this, value, id);
        if (dr == null) {
            dr = getDrawable(id);
            if (BuildConfig.DEBUG && (id & APP_ID_MASK) == APP_ID_MASK) {
                Log.v("loadDrawable(Resources, TypedValue, int) return null, retry from getDrawable value:{} from :{} result:{} ", toString(value), this, dr);
            }
        }
        return dr;
    }

    /**
     * 和Resource.getDrawable(int)一样的加载资源实现, 增加在加载图片资源时内存占用调整
     *
     * @param res
     * @param value
     * @param id
     * @return
     * @throws NotFoundException
     */
    protected final Drawable loadDrawable(Resources res, TypedValue value, int id) throws NotFoundException {

        loadingFlag.set(res);

        boolean isColorDrawable = isColor(value);
        Drawable dr = null;
        if (isColorDrawable) {
            dr = new ColorDrawable(value.data);
        } else {
            if (value.string == null) {
                throw new NotFoundException("Resource is not a Drawable (color or path): " + value);
            }

            String file = value.string.toString();
            if (file.endsWith(".xml")) {
                try {
                    XmlResourceParser rp = ReflectUtil.invoke(res, "loadXmlResourceParser", loadXmlResourceParserParam, file, id, value.assetCookie, "drawable");
                    dr = Drawable.createFromXml(res, rp);
                    rp.close();
                } catch (Exception e) {
                    Log.w(e, "File {} from drawable resource ID #0x{} not found in {}", file, Integer.toHexString(id), res);
                }
            } else {
                try {

                    InputStream is = ReflectUtil.invoke(res.getAssets(), "openNonAsset", openNonAssetParam, value.assetCookie, file, AssetManager.ACCESS_STREAMING);
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    if (SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
                        opts.inPreferredConfig = Bitmap.Config.RGB_565;
                        ReflectUtil.set(opts, "inNativeAlloc", true);
                    }
                    opts.inPurgeable = true;
                    opts.inInputShareable = true;
                    dr = Drawable.createFromResourceStream(res, value, is, file, opts);
                    is.close();
                } catch (Exception e) {
                    Log.w("File :{} from drawable resource ID #0x{} not found in :{}", file, Integer.toHexString(id), res);
                    Log.w(e);
                }
            }
        }

        if (dr != null) {
            dr.setChangingConfigurations(value.changingConfigurations);
        }

        if (BuildConfig.DEBUG && (id & APP_ID_MASK) == APP_ID_MASK) {
            Log.v("load value:{} from :{} result:{} ", toString(value), res, dr);
        }

        loadingFlag.remove();

        return dr;
    }

    public ColorStateList loadColorStateList(int id) throws NotFoundException {
        TypedValue value = typedValueCache;
        this.getValue(id, value, true);
        ColorStateList csl = loadColorStateList(this, value, id);
        if (csl == null) {
            csl = getColorStateList(id);
            if (BuildConfig.DEBUG && (id & APP_ID_MASK) == APP_ID_MASK) {
                Log.v("loadColorStateList(Resources, TypedValue, int) return null, retry load value:{} from :{} result:{} ", toString(value), this, csl);
            }
        }
        return csl;
    }

    protected final ColorStateList loadColorStateList(Resources res, TypedValue value, int id) throws NotFoundException {

        loadingFlag.set(res);

        ColorStateList csl = null;
        if (isColor(value)) {
            csl = ColorStateList.valueOf(value.data);
            return csl;
        }

        if (value.string == null) {
            throw new NotFoundException("Resource is not a ColorStateList (color or path): " + value);
        }

        String file = value.string.toString();

        if (file.endsWith(".xml")) {
            try {
                XmlResourceParser rp = ReflectUtil.invoke(res, "loadXmlResourceParser", loadXmlResourceParserParam, file, id, value.assetCookie, "colorstatelist");
                csl = ColorStateList.createFromXml(res, rp);
                rp.close();
            } catch (Exception e) {
                Log.w("File {} from color state list resource ID #0x{} not found in {}", file, Integer.toHexString(id), res);
                Log.w(e);
            }
        } else {
            throw new NotFoundException("File " + file + " from drawable resource ID #0x" + Integer.toHexString(id) + ": .xml extension required");
        }

        if (BuildConfig.DEBUG && (id & APP_ID_MASK) == APP_ID_MASK) {
            Log.v("load value:{} from :{} result:{} ", toString(value), res, csl);
        }

        loadingFlag.remove();

        return csl;
    }

    public void clearCache() {
        resourceNameIdCache = new int[RESOURCE_NAME_CACHE_SIZE];
        resourceNameCache = new String[RESOURCE_NAME_CACHE_SIZE];
    }

    /**
     * 判断当前Resources是否正在加载资源， 防止资源加载嵌套
     *
     * @return
     */
    public boolean isCurrentLoading() {
        return loadingFlag.get() != null;
    }
}
