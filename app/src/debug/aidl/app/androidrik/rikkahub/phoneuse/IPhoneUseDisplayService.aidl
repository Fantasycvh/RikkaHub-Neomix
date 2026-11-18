package app.androidrik.rikkahub.phoneuse;

import android.view.Surface;
import android.os.Bundle;

interface IPhoneUseDisplayService {
    int create(in Surface surface, IBinder owner) = 0;
    boolean launch(String packageName) = 1;
    boolean back() = 2;
    void release() = 3;
    List<Bundle> listLaunchers() = 4;
    boolean openUrl(String packageName, String url) = 5;
    void destroy() = 16777114;
}
