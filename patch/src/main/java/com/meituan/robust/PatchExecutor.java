package com.meituan.robust;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.meituan.robust.patch.resources.RobustResources;
import com.meituan.robust.patch.resources.recover.ApkRecover;
import com.meituan.robust.patch.resources.service.RobustRecoverService;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import dalvik.system.DexClassLoader;

/**
 * Created by c_kunwu on 16/5/20.
 * if you need,you can override PatchExecutor
 */
public class PatchExecutor extends Thread {
    protected Context context;
    protected PatchManipulate patchManipulate;
    protected RobustCallBack robustCallBack;

    public PatchExecutor(Context context, PatchManipulate patchManipulate, RobustCallBack robustCallBack) {
        this.context = context.getApplicationContext();
        this.patchManipulate = patchManipulate;
        this.robustCallBack = robustCallBack;
    }

    @Override
    public void run() {
        try {
            //拉取补丁列表
            List<Patch> patches = fetchPatchList();
            //应用补丁列表
            applyPatchList(patches);
        } catch (Throwable t) {
            Log.e("robust", "PatchExecutor run", t);
            robustCallBack.exceptionNotify(t, "class:PatchExecutor,method:run,line:36");
        }
    }

    /**
     * 拉取补丁列表
     */
    protected List<Patch> fetchPatchList() {
        return patchManipulate.fetchPatchList(context);
    }

    /**
     * 应用补丁列表
     */
    protected void applyPatchList(List<Patch> patches) {
        if (null == patches || patches.isEmpty()) {
            return;
        }
        Log.d("robust", " patchManipulate list size is " + patches.size());

        //        1.patches dex + resources
//        2.patches dex
//        3.patches resources
//1 + 3 .按照name从大到小排序 (dex都应用，但是资源只用第一个已经recover的）
        List<Patch> dexPatches = new ArrayList<>();
        List<Patch> resourcesPatches = new ArrayList<>();
        List<Patch> dexAndResourcesPatches = new ArrayList<>();

        //先下载
        for (Patch p : patches) {
            if (p.isAppliedSuccess()) {
                Log.d("robust", "p.isAppliedSuccess() skip " + p.getLocalPath());
                continue;
            }
            if (patchManipulate.ensurePatchExist(p)) {

                if (patchManipulate.verifyPatch(context, p)) {

                    int patchType = PatchTypeUtil.getPatchType(p);

                    if (PatchTypeUtil.isDexAndResourceType(patchType)) {
                        if (ApkRecover.isRecovered(context, p.getName(), p.getMd5())) {
                            dexAndResourcesPatches.add(p);
                        } else {
                            String resourceTmpPath = ApkRecover.copyPatch2TmpPath(context, p.getName(), p.getMd5(), p.getTempPath());
                            if (!TextUtils.isEmpty(resourceTmpPath)) {
                                RobustRecoverService.startRobustRecoverService(context, p.getName(), p.getMd5(), resourceTmpPath);
                            }
                        }
                        continue;
                    }

                    if (PatchTypeUtil.isDexType(patchType)) {
                        dexPatches.add(p);
                        continue;
                    }

                    if (PatchTypeUtil.isResourceType(patchType)) {
                        if (ApkRecover.isRecovered(context, p.getName(), p.getMd5())) {
                            resourcesPatches.add(p);
                        } else {
                            String resourceTmpPath = ApkRecover.copyPatch2TmpPath(context, p.getName(), p.getMd5(), p.getTempPath());
                            if (!TextUtils.isEmpty(resourceTmpPath)) {
                                RobustRecoverService.startRobustRecoverService(context, p.getName(), p.getMd5(), resourceTmpPath);
                            }
                        }

                        continue;
                    }

                } else {
                    robustCallBack.logNotify("verifyPatch failure, patch info:" + "id = " + p.getName() + ",md5 = " + p.getMd5(), "class:PatchExecutor method:patch line:107");
                }
            }
        }

        applyDexTypePatches(dexPatches);
        applyOtherPatches(resourcesPatches, dexAndResourcesPatches);

    }

    private void applyDexTypePatches(List<Patch> patches) {
        for (Patch p : patches) {
//            if (p.isAppliedSuccess()) {
//                Log.d("robust", "p.isAppliedSuccess() skip " + p.getLocalPath());
//                continue;
//            }
//            if (patchManipulate.ensurePatchExist(p)) {
//
//                if (!patchManipulate.verifyPatch(context, p)) {
//                    robustCallBack.logNotify("verifyPatch failure, patch info:" + "id = " + p.getName() + ",md5 = " + p.getMd5(), "class:PatchExecutor method:patch line:107");
//                    continue;
//                }

            boolean currentPatchResult = false;
            try {
                currentPatchResult = patch(context, p);
            } catch (Throwable t) {
                robustCallBack.exceptionNotify(t, "class:PatchExecutor method:applyPatchList line:69");
            }
            if (currentPatchResult) {
                //设置patch 状态为成功
                p.setAppliedSuccess(true);
                //统计PATCH成功率 PATCH成功
                robustCallBack.onPatchApplied(true, p);

            } else {
                //统计PATCH成功率 PATCH失败
                robustCallBack.onPatchApplied(false, p);
            }

            Log.d("robust", "patch LocalPath:" + p.getLocalPath() + ",apply result " + currentPatchResult);

//            }
        }
    }

    private void applyOtherPatches(List<Patch> resourcesPatches, List<Patch> dexAndResourcesPatches) {
        List<Patch> patches = new ArrayList<>();
        patches.addAll(resourcesPatches);
        patches.addAll(dexAndResourcesPatches);
        if (patches.isEmpty()) {
            return;
        }
        //order by name desc
        Collections.sort(patches, new Comparator<Patch>() {
            @Override
            public int compare(Patch p1, Patch p2) {
                return p2.getName().compareToIgnoreCase(p1.getName());
            }
        });

        {
            Patch patchResApply = patches.get(0);
            RobustResources.resFix(context, patchResApply.getName(), patchResApply.getMd5());
        }

        for (Patch p : patches) {
            RobustResources.libFix(context, p.getName(), p.getMd5());
        }

        //apply dex
        applyDexTypePatches(dexAndResourcesPatches);

    }


    protected boolean patch(Context context, Patch patch) {

        DexClassLoader classLoader = new DexClassLoader(patch.getTempPath(), context.getCacheDir().getAbsolutePath(),
                null, PatchExecutor.class.getClassLoader());
        patch.delete(patch.getTempPath());

        Class patchClass, oldClass;

        Class patchsInfoClass;
        PatchesInfo patchesInfo = null;
        try {
            Log.d("robust", "PatchsInfoImpl name:" + patch.getPatchesInfoImplClassFullName());
            patchsInfoClass = classLoader.loadClass(patch.getPatchesInfoImplClassFullName());
            patchesInfo = (PatchesInfo) patchsInfoClass.newInstance();
            Log.d("robust", "PatchsInfoImpl ok");
        } catch (Throwable t) {
            robustCallBack.exceptionNotify(t, "class:PatchExecutor method:patch line:108");
            Log.e("robust", "PatchsInfoImpl failed,cause of" + t.toString());
            t.printStackTrace();
        }

        if (patchesInfo == null) {
            robustCallBack.logNotify("patchesInfo is null, patch info:" + "id = " + patch.getName() + ",md5 = " + patch.getMd5(), "class:PatchExecutor method:patch line:114");
            return false;
        }

        //classes need to patch
        List<PatchedClassInfo> patchedClasses = patchesInfo.getPatchedClassesInfo();
        if (null == patchedClasses || patchedClasses.isEmpty()) {
            robustCallBack.logNotify("patchedClasses is null or empty, patch info:" + "id = " + patch.getName() + ",md5 = " + patch.getMd5(), "class:PatchExecutor method:patch line:122");
            return false;
        }

        for (PatchedClassInfo patchedClassInfo : patchedClasses) {
            String patchedClassName = patchedClassInfo.patchedClassName;
            String patchClassName = patchedClassInfo.patchClassName;
            if (TextUtils.isEmpty(patchedClassName) || TextUtils.isEmpty(patchClassName)) {
                robustCallBack.logNotify("patchedClasses or patchClassName is empty, patch info:" + "id = " + patch.getName() + ",md5 = " + patch.getMd5(), "class:PatchExecutor method:patch line:131");
                continue;
            }
            Log.d("robust", "current path:" + patchedClassName);
            try {
                oldClass = classLoader.loadClass(patchedClassName.trim());
                Field[] fields = oldClass.getDeclaredFields();
                Log.d("robust", "oldClass :" + oldClass + "     fields " + fields.length);
                Field changeQuickRedirectField = null;
                for (Field field : fields) {
                    if (TextUtils.equals(field.getType().getCanonicalName(), ChangeQuickRedirect.class.getCanonicalName()) && TextUtils.equals(field.getDeclaringClass().getCanonicalName(), oldClass.getCanonicalName())) {
                        changeQuickRedirectField = field;
                        break;
                    }
                }
                if (changeQuickRedirectField == null) {
                    robustCallBack.logNotify("changeQuickRedirectField  is null, patch info:" + "id = " + patch.getName() + ",md5 = " + patch.getMd5(), "class:PatchExecutor method:patch line:147");
                    Log.d("robust", "current path:" + patchedClassName + " something wrong !! can  not find:ChangeQuickRedirect in" + patchClassName);
                    continue;
                }
                Log.d("robust", "current path:" + patchedClassName + " find:ChangeQuickRedirect " + patchClassName);
                try {
                    patchClass = classLoader.loadClass(patchClassName);
                    Object patchObject = patchClass.newInstance();
                    changeQuickRedirectField.setAccessible(true);
                    changeQuickRedirectField.set(null, patchObject);
                    Log.d("robust", "changeQuickRedirectField set sucess " + patchClassName);
                } catch (Throwable t) {
                    Log.e("robust", "patch failed! ");
                    t.printStackTrace();
                    robustCallBack.exceptionNotify(t, "class:PatchExecutor method:patch line:163");
                }
            } catch (Throwable t) {
                Log.e("robust", "patch failed! ");
                t.printStackTrace();
                robustCallBack.exceptionNotify(t, "class:PatchExecutor method:patch line:169");
            }
        }
        Log.d("robust", "patch finished ");
        return true;
    }

}
