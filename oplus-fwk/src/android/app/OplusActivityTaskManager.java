package android.app;

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.os.RemoteException;
import com.oplus.app.OplusAppInfo;
import java.util.ArrayList;
import java.util.List;

/* loaded from: classes.dex */
public class OplusActivityTaskManager extends OplusBaseActivityTaskManager implements IOplusActivityTaskManager {
    public static OplusActivityTaskManager getInstance() {
        return LazyHolder.INSTANCE;
    }

    /* loaded from: classes.dex */
    public static class LazyHolder {
        private static final OplusActivityTaskManager INSTANCE = new OplusActivityTaskManager();

        private LazyHolder() {
        }
    }

    @Override // android.app.IOplusActivityTaskManager
    public ComponentName getTopActivityComponentName() throws RemoteException {
        return null;
    }

    @Override // android.app.IOplusActivityTaskManager
    public ApplicationInfo getTopApplicationInfo() throws RemoteException {
        return null;
    }

    @Override // android.app.IOplusActivityTaskManager
    public List<OplusAppInfo> getAllTopAppInfos() throws RemoteException {
        return new ArrayList<>();
    }

    @Override // android.app.IOplusActivityTaskManager
    public List<OplusAppInfo> getAllTopApps() throws RemoteException {
        return new ArrayList<>();
    }
}
