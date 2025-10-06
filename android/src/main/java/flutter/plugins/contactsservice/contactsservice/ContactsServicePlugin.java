package flutter.plugins.contactsservice.contactsservice;

import android.annotation.TargetApi;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.provider.BaseColumns;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

import static android.app.Activity.RESULT_CANCELED;
import static android.provider.ContactsContract.CommonDataKinds;
import static android.provider.ContactsContract.CommonDataKinds.Email;
import static android.provider.ContactsContract.CommonDataKinds.Organization;
import static android.provider.ContactsContract.CommonDataKinds.Phone;
import static android.provider.ContactsContract.CommonDataKinds.StructuredName;
import static android.provider.ContactsContract.CommonDataKinds.StructuredPostal;

@TargetApi(Build.VERSION_CODES.ECLAIR)
public class ContactsServicePlugin implements MethodCallHandler, FlutterPlugin, ActivityAware {

  private static final int FORM_OPERATION_CANCELED = 1;
  private static final int FORM_COULD_NOT_BE_OPEN = 2;

  private static final String LOG_TAG = "flutter_contacts";
  private ContentResolver contentResolver;
  private MethodChannel methodChannel;
  private BaseContactsServiceDelegate delegate;
  private Resources resources;

  private final ExecutorService executor =
          new ThreadPoolExecutor(0, 10, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(1000));

  // REMOVIDO: initDelegateWithRegister e registerWith (somente v2)

  private void initInstance(BinaryMessenger messenger, Context context) {
    methodChannel = new MethodChannel(messenger, "github.com/clovisnicolas/flutter_contacts");
    methodChannel.setMethodCallHandler(this);
    this.contentResolver = context.getContentResolver();
  }

  @Override
  public void onAttachedToEngine(FlutterPluginBinding binding) {
    resources = binding.getApplicationContext().getResources();
    initInstance(binding.getBinaryMessenger(), binding.getApplicationContext());
    this.delegate = new ContactServiceDelegate(binding.getApplicationContext());
  }

  @Override
  public void onDetachedFromEngine(FlutterPluginBinding binding) {
    methodChannel.setMethodCallHandler(null);
    methodChannel = null;
    contentResolver = null;
    this.delegate = null;
    resources = null;
  }

  @Override
  public void onMethodCall(MethodCall call, Result result) {
    switch(call.method){
      case "getContacts": {
        this.getContacts(call.method, (String)call.argument("query"), (boolean)call.argument("withThumbnails"), (boolean)call.argument("photoHighResolution"), (boolean)call.argument("orderByGivenName"), (boolean)call.argument("androidLocalizedLabels"), result);
        break;
      } case "getContactsForPhone": {
        this.getContactsForPhone(call.method, (String)call.argument("phone"), (boolean)call.argument("withThumbnails"), (boolean)call.argument("photoHighResolution"), (boolean)call.argument("orderByGivenName"), (boolean)call.argument("androidLocalizedLabels"), result);
        break;
      } case "getContactsForEmail": {
        this.getContactsForEmail(call.method, (String)call.argument("email"), (boolean)call.argument("withThumbnails"), (boolean)call.argument("photoHighResolution"), (boolean)call.argument("orderByGivenName"), (boolean)call.argument("androidLocalizedLabels"), result);
        break;
      } case "getAvatar": {
        final Contact contact = Contact.fromMap((HashMap)call.argument("contact"));
        this.getAvatar(contact, (boolean)call.argument("photoHighResolution"), result);
        break;
      } case "addContact": {
        final Contact contact = Contact.fromMap((HashMap)call.arguments);
        if (this.addContact(contact)) {
          result.success(null);
        } else {
          result.error(null, "Failed to add the contact", null);
        }
        break;
      } case "deleteContact": {
        final Contact contact = Contact.fromMap((HashMap)call.arguments);
        if (this.deleteContact(contact)) {
          result.success(null);
        } else {
          result.error(null, "Failed to delete the contact, make sure it has a valid identifier", null);
        }
        break;
      } case "updateContact": {
        final Contact contact = Contact.fromMap((HashMap)call.arguments);
        if (this.updateContact(contact)) {
          result.success(null);
        } else {
          result.error(null, "Failed to update the contact, make sure it has a valid identifier", null);
        }
        break;
      } case "openExistingContact" :{
        final Contact contact = Contact.fromMap((HashMap)call.argument("contact"));
        final boolean localizedLabels = call.argument("androidLocalizedLabels");
        if (delegate != null) {
          delegate.setResult(result);
          delegate.setLocalizedLabels(localizedLabels);
          delegate.openExistingContact(contact);
        } else {
          result.success(FORM_COULD_NOT_BE_OPEN);
        }
        break;
      } case "openContactForm": {
        final boolean localizedLabels = call.argument("androidLocalizedLabels");
        if (delegate != null) {
          delegate.setResult(result);
          delegate.setLocalizedLabels(localizedLabels);
          delegate.openContactForm();
        } else {
          result.success(FORM_COULD_NOT_BE_OPEN);
        }
        break;
      } case "openDeviceContactPicker": {
        final boolean localizedLabels = call.argument("androidLocalizedLabels");
        openDeviceContactPicker(result, localizedLabels);
        break;
      } default: {
        result.notImplemented();
        break;
      }
    }
  }

  // ... RESTANTE DO CÓDIGO IGUAL AQUI (PROJECTION, getContacts, getContactsForPhone, etc.) ...

  private class ContactServiceDelegate extends BaseContactsServiceDelegate {
    private final Context context;
    private ActivityPluginBinding activityPluginBinding;

    ContactServiceDelegate(Context context) {
      this.context = context;
    }

    void bindToActivity(ActivityPluginBinding activityPluginBinding) {
      this.activityPluginBinding = activityPluginBinding;
      this.activityPluginBinding.addActivityResultListener(this);
    }

    void unbindActivity() {
      this.activityPluginBinding.removeActivityResultListener(this);
      this.activityPluginBinding = null;
    }

    @Override
    void startIntent(Intent intent, int request) {
      if (this.activityPluginBinding != null) {
        if (intent.resolveActivity(context.getPackageManager()) != null) {
          activityPluginBinding.getActivity().startActivityForResult(intent, request);
        } else {
          finishWithResult(FORM_COULD_NOT_BE_OPEN);
        }
      } else {
        context.startActivity(intent);
      }
    }
  }

  // ... RESTANTE DO CÓDIGO IGUAL (GetContactsTask, addContact, updateContact, etc.) ...

}
