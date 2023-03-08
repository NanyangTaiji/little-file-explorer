package com.martinmimigames.simplefileexplorer;

import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.StatFs;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

import mg.utils.clipboard.v1.ClipBoard;
import mg.utils.notify.ToastHelper;

public class MainActivity extends Activity {
  private static final int ONGOING_OPERATION_ID = Integer.MAX_VALUE;
  private static final int LOADING_VIEW_ID = Integer.MAX_VALUE - 1;
  private static final String FILE_PATH_FLAG = "file path";
  private static final int HIGHLIGHT_COLOR = 0xFF00A4C9;
  private final ShortTabOnButton shortTabOnButton;
  private final LongPressOnButton longPressOnButton;
  private final ArrayList<File> currentSelectedFiles;
  private final ThreadPoolExecutor executor;
  private final FileOpener fopen;
  private final State appState;
  private int hasOperations;
  private LinearLayout ll;
  private int width;
  private Bitmap fileImage, pictureImage, audioImage, videoImage, unknownImage, archiveImage, folderImage;
  private String filePath;
  private File parent;
  private boolean isCut, isCopy;
  private PermissionManager permissionManager;
  private Dialog openListDialog;
  private Dialog deleteConfirmationDialog;
  private Dialog createDirectoryDialog;
  private Dialog renameDialog;

  public MainActivity() {
    shortTabOnButton = new ShortTabOnButton();
    longPressOnButton = new LongPressOnButton();
    currentSelectedFiles = new ArrayList<>(3);
    executor = new ScheduledThreadPoolExecutor(1);
    fopen = new FileOpener(this);
    appState = new State();

    hasOperations = 0;
  }

  private void setupUI() {
    setupTopBar();
    setupMenu();
    //setupDriveList(); in onStart for reload
    setupSelectMenu();
    setupSingleSelectMenu();
    setupPasteMenu();
    setupOpenListDialogue();
    setupRenameDialog();
    setupDeleteDialog();
    setupCreateDirectoryDialog();
  }

  @Override
  protected void onCreate(final Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    permissionManager = new PermissionManager(this);

    Intent intent = getIntent();
    switch (intent.getAction()) {
      case Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_GET_CONTENT -> {
        fopen.isRequestDocument = true;
        setResult(RESULT_CANCELED);
      }
      default -> fopen.isRequestDocument = false;
    }

    ll = findViewById(R.id.list);

    setupBitmaps();
    setupUI();

    if (savedInstanceState != null) {
      filePath = savedInstanceState.getString(FILE_PATH_FLAG);
    }

    // jump start app state
    appState.current.start();
  }

  @Override
  public void onSaveInstanceState(Bundle savedInstanceState) {
    super.onSaveInstanceState(savedInstanceState);
    savedInstanceState.putString(FILE_PATH_FLAG, filePath);
  }

  @Override
  protected void onStart() {
    super.onStart();
    final File[] rootDirectories = FileOperation.getAllStorages(this);
    setupDriveList(rootDirectories);

    executor.execute(() -> {
      if (filePath != null) {
        if (checkPermission())
          listItem(new File(filePath));
      } else {
        for (File folder : rootDirectories) {
          if (checkPermission()) listItem(folder);
          break;
        }
      }
    });
  }

  private boolean checkPermission() {

    permissionManager.getPermission(READ_EXTERNAL_STORAGE, "Storage access is required", false);
    permissionManager.getPermission(WRITE_EXTERNAL_STORAGE, "Storage access is required", false);

    return permissionManager.havePermission(new String[]{READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE});
  }

  private void listItem(final File folder) {
    String info = "Name: " + folder.getName() + "\n";
    if (Build.VERSION.SDK_INT >= 9) {
      StatFs stat = new StatFs(folder.getPath());
      long bytesAvailable = Build.VERSION.SDK_INT >= 18 ?
        stat.getBlockSizeLong() * stat.getAvailableBlocksLong() :
        (long) stat.getBlockSize() * stat.getAvailableBlocks();
      info += "Available size: " + FileOperation.getReadableMemorySize(bytesAvailable) + "\n";
      if (Build.VERSION.SDK_INT >= 18) {
        bytesAvailable = stat.getTotalBytes();
        info += "Capacity size: " + FileOperation.getReadableMemorySize(bytesAvailable) + "\n";
      }
    }
    final String finalInfo = info;
    runOnUiThread(() -> {
      ll.removeAllViews();
      ((TextView) findViewById(R.id.title)).setText(filePath);
      addDialog(finalInfo, 16);

      addIdDialog("Loading...", 16, LOADING_VIEW_ID);
      addIdDialog("cutting/copying/deleting files...", 16, ONGOING_OPERATION_ID);
      if (hasOperations == 0)
        setViewVisibility(ONGOING_OPERATION_ID, View.GONE);
    });

    parent = folder.getParentFile();

    filePath = folder.getPath();
    if (folderAccessible(folder)) {
      final File[] items = folder.listFiles();
      assert items != null;
      sort(items);
      if (items.length == 0) {
        addDialog("Empty folder!", 16);
      } else {
        String lastLetter = "";
        boolean hasFolders = false;
        for (File item : items) {
          if (item.isDirectory()) {
            if (!hasFolders) {
              addDialog("Folders:", 18);
              hasFolders = true;
            }
            if (item.getName().substring(0, 1)
              .compareToIgnoreCase(lastLetter) > 0) {
              lastLetter = item.getName().substring(0, 1).toUpperCase();
              addDialog(lastLetter, 16);
            }
            addDirectory(item);
          }
        }
        lastLetter = "";
        boolean hasFiles = false;
        for (File item : items)
          if (item.isFile()) {
            if (!hasFiles) {
              addDialog("Files:", 18);
              hasFiles = true;
            }
            if (item.getName().substring(0, 1)
              .compareToIgnoreCase(lastLetter) > 0) {
              lastLetter = item.getName().substring(0, 1).toUpperCase();
              addDialog(lastLetter, 16);
            }
            switch (FileProvider.getFileType(item).split("/")[0]) {
              case "image" -> addItem(getImageView(pictureImage), item);
              case "video" -> addItem(getImageView(videoImage), item);
              case "audio" -> addItem(getImageView(audioImage), item);
              case "application" -> {
                if (FileProvider.getFileType(item).contains("application/octet-stream"))
                  addItem(getImageView(unknownImage), item);
                else
                  addItem(getImageView(archiveImage), item);
              }
              case "text" -> addItem(getImageView(fileImage), item);
              default -> addItem(getImageView(unknownImage), item);
            }
          }
      }
    } else {
      if (filePath.contains("Android/data") || filePath.contains("Android/obb")) {
        addDialog("For android 11 or higher, Android/data and Android/obb is refused access.\n", 16);
      } else addDialog("Access Denied", 16);
    }
    runOnUiThread(() -> ll.removeView(findViewById(LOADING_VIEW_ID)));
  }

  private void addIdDialog(final String dialog, final int textSize, final int id) {
    final TextView tv = new TextView(this);
    tv.setText(dialog);
    tv.setBackgroundColor(Color.TRANSPARENT);
    tv.setTextColor(Color.WHITE);
    tv.setTextSize(textSize);
    tv.setId(id);

    runOnUiThread(() -> ll.addView(tv));
  }

  private void addDialog(final String dialog, final int textSize) {
    addIdDialog(dialog, textSize, View.NO_ID);
  }

  private void sort(final File[] items) {
    // for every item
    for (int i = 0; i < items.length; i++) {
      // j = for every next item
      for (int j = i + 1; j < items.length; j++) {
        // if larger than next
        if (items[i].toString()
          .compareToIgnoreCase(items[j].toString()) > 0) {
          File temp = items[i];
          items[i] = items[j];
          items[j] = temp;
        }
      }
    }
  }

  //return true if can read
  private boolean folderAccessible(final File folder) {
    try {
      return folder.canRead();
    } catch (SecurityException e) {
      return false;
    }
  }

  private void addDirectory(final File folder) {
    addItem(getImageView(folderImage), folder);
  }

  private void addItem(final ImageView imageView, final File file) {
    new Item(imageView, file);
  }

  private ImageView getImageView(final Bitmap bitmap) {
    final ImageView imageView = new ImageView(this);
    imageView.setImageBitmap(bitmap);
    final int width10 = width / 8;
    imageView.setAdjustViewBounds(true);
    imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
    imageView.setMinimumWidth(width10);
    imageView.setMinimumHeight(width10);
    imageView.setMaxWidth(width10);
    imageView.setMaxHeight(width10);
    return imageView;
  }

  private void selectFiles(File file, View view) {
    if (!currentSelectedFiles.contains(file)) {
      currentSelectedFiles.add(file);
      view.setBackgroundColor(HIGHLIGHT_COLOR);
    } else {
      currentSelectedFiles.remove(file);
      view.setBackgroundColor(Color.TRANSPARENT);
    }
    switch (currentSelectedFiles.size()){
      case 1 -> {
        appState.change(appState.select);
        setViewVisibility(R.id.single_select_operation, View.VISIBLE);
      }
      case 0 -> appState.change(appState.idle);
      default -> setViewVisibility(R.id.single_select_operation, View.GONE);
    }
  }

  private void clearHighlight() {
    forEachItem(item -> item.setBackgroundColor(Color.TRANSPARENT));
  }

  @Override
  public void onBackPressed() {
    if (!returnToParent())
      super.onBackPressed();
  }

  /**
   * tries to go back to a parent folder
   *
   * @return false if failed, else true
   */
  boolean returnToParent() {
    if (parent == null || !folderAccessible(parent))
      return false;
    executor.execute(() -> listItem(parent));
    return true;
  }

  private void setupTopBar() {
    findViewById(R.id.back_button).setOnClickListener(v -> returnToParent());

    findViewById(R.id.menu_button).setOnClickListener(v -> {
      final View menuList = findViewById(R.id.menu_list);
      if (menuList.isShown()) {
        menuList.setVisibility(View.GONE);
        if (appState.current == appState.select)
          setViewVisibility(R.id.quick_selection, View.VISIBLE);
      } else {
        menuList.setVisibility(View.VISIBLE);
        setViewVisibility(R.id.quick_selection, View.GONE);
      }
    });
  }

  private void setupMenu() {
    setViewVisibility(R.id.menu_list, View.GONE);
    findViewById(R.id.menu_create_new_directory)
      .setOnClickListener(v -> {
        createDirectoryDialog.show();
        setViewVisibility(R.id.menu_list, View.GONE);
      });
    findViewById(R.id.menu_quick_switch)
      .setOnClickListener(v -> {
        setViewVisibility(R.id.menu_list, View.GONE);
        final View driveList = findViewById(R.id.drive_list);
        if (driveList.isShown())
          driveList.setVisibility(View.GONE);
        else
          driveList.setVisibility(View.VISIBLE);
      });
  }

  private void setupDriveList(final File[] rootDirectories) {
    final LinearLayout list = findViewById(R.id.drive_list);
    list.removeAllViews();
    for (File file : rootDirectories) {
      final Button entry = new Button(this);
      entry.setLayoutParams(
        new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          LinearLayout.LayoutParams.WRAP_CONTENT
        )
      );
      entry.setText(file.getPath());
      entry.setOnClickListener(v -> {
        executor.execute(() -> listItem(file));
        setViewVisibility(R.id.drive_list, View.GONE);
      });
      list.addView(entry);
    }
    list.setVisibility(View.GONE);
  }

  private void setupSingleSelectMenu() {
    findViewById(R.id.single_select_copy_path).setOnClickListener(v -> {
      copyFilePath();
      appState.change(appState.idle);
    });

    findViewById(R.id.single_select_rename).setOnClickListener(v -> {
      ((EditText) renameDialog.findViewById(R.id.new_name)).setText(currentSelectedFiles.get(0).getName());
      renameDialog.show();
    });
  }

  private void setupSelectMenu() {
    findViewById(R.id.select_cut).setOnClickListener(v -> {
      isCut = true;
      appState.change(appState.paste);
    });
    findViewById(R.id.select_copy).setOnClickListener(v -> {
      isCopy = true;
      appState.change(appState.paste);
    });

    findViewById(R.id.select_delete).setOnClickListener(v -> {
      StringBuilder list = new StringBuilder("Delete the following files/folders?\n");
      for (int i = 0; i < currentSelectedFiles.size(); i++) {
        list.append(currentSelectedFiles.get(i).getName());
        if (i < currentSelectedFiles.size() - 1) {
          list.append(",\n");
        }
      }
      ((TextView) deleteConfirmationDialog.findViewById(R.id.delete_list)).setText(list.toString());
      deleteConfirmationDialog.show();
    });
    findViewById(R.id.select_cancel).setOnClickListener(v -> appState.change(appState.idle));

    findViewById(R.id.select_all)
      .setOnClickListener(v -> forEachItem(item -> {
          if (!currentSelectedFiles.contains(item.file)) {
            selectFiles(item.file, item);
          }
        })
      );

    findViewById(R.id.invert_select_all)
      .setOnClickListener(v -> forEachItem(item -> selectFiles(item.file, item)));

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.DONUT) {
      findViewById(R.id.share_selected)
        .setOnClickListener(v -> {
          fopen.share(getCurrentSelectedFilesList());
          appState.change(appState.idle);
        });
    } else {
      setViewVisibility(R.id.share_selected, View.GONE);
    }
  }

  private void setupPasteMenu() {
    findViewById(R.id.paste_paste)
      .setOnClickListener(
        v -> new Thread(() -> {
          final int currentOperation = hasOperations + 1;
          hasOperations = currentOperation;
          if (findViewById(ONGOING_OPERATION_ID) != null)
            setViewVisibility(ONGOING_OPERATION_ID, View.VISIBLE);
          final ArrayList<File> selectedFiles = new ArrayList<>(currentSelectedFiles.size());
          selectedFiles.addAll(currentSelectedFiles);
          appState.change(appState.idle);
          var dst = filePath;
          if (isCut) {
            for (int i = 0; i < selectedFiles.size(); i++) {
              File file = selectedFiles.get(i);
              FileOperation.move(file, new File(dst, file.getName()));
            }
          } else if (isCopy) {
            for (int i = 0; i < selectedFiles.size(); i++) {
              final File file = selectedFiles.get(i);
              FileOperation.copy(file, new File(dst, file.getName()));
            }
          }

          if (hasOperations == currentOperation)
            hasOperations = 0;
          if (findViewById(ONGOING_OPERATION_ID) != null)
            setViewVisibility(ONGOING_OPERATION_ID, View.GONE);
          selectedFiles.clear();
          executor.execute(() -> listItem(new File(filePath)));
        }).start());
    findViewById(R.id.paste_cancel).setOnClickListener(v -> appState.change(appState.idle));
  }

  private void setViewVisibility(int id, int visibility) {
    runOnUiThread(() -> findViewById(id).setVisibility(visibility));
  }

  private void copyFilePath(){
    ClipBoard.copyText(this, currentSelectedFiles.get(0).getAbsolutePath());
    ToastHelper.showShort(this, "copied path");
    appState.change(appState.idle);
  }

  private void setupOpenListDialogue() {
    openListDialog = new Dialog(this, R.style.app_theme_dialog);
    openListDialog.setContentView(R.layout.open_list);
    openListDialog.findViewById(R.id.open_list_open).setOnClickListener(v -> {
      openListDialog.dismiss();
      fopen.open(currentSelectedFiles.get(0));
      appState.change(appState.idle);
    });

    if (fopen.isRequestDocument) {
      openListDialog.findViewById(R.id.open_list_share).setVisibility(View.GONE);
    } else {
      openListDialog.findViewById(R.id.open_list_share).setOnClickListener(v -> {
        openListDialog.dismiss();
        fopen.share(getCurrentSelectedFilesList());
        appState.change(appState.idle);
      });
    }
    openListDialog.findViewById(R.id.open_list_cancel).setOnClickListener(v -> {
      openListDialog.dismiss();
      appState.change(appState.idle);
    });
    openListDialog.setOnCancelListener(d -> appState.change(appState.idle));
  }

  File[] getCurrentSelectedFilesList() {
    var files = new File[currentSelectedFiles.size()];
    for (int i = 0; i < currentSelectedFiles.size(); i++) {
      files[i] = currentSelectedFiles.get(i);
    }
    return files;
  }

  private void setupRenameDialog() {
    renameDialog = new Dialog(this, R.style.app_theme_dialog);
    renameDialog.setTitle("Rename File/Folder");
    renameDialog.setContentView(R.layout.rename_confirmation);
    renameDialog.findViewById(R.id.rename_rename)
      .setOnClickListener(
        v -> {
          renameDialog.dismiss();
          String name = ((EditText) renameDialog.findViewById(R.id.new_name)).getText().toString();
          File src = currentSelectedFiles.get(0);
          File dst = new File(src.getParent(), name);
          FileOperation.move(src, dst);
          appState.change(appState.idle);
          executor.execute(() -> listItem(new File(filePath)));
        }
      );
    renameDialog.findViewById(R.id.rename_cancel)
      .setOnClickListener(
        v -> {
          renameDialog.dismiss();
          appState.change(appState.idle);
        }
      );
  }

  private void setupDeleteDialog() {
    deleteConfirmationDialog = new Dialog(this, R.style.app_theme_dialog);
    deleteConfirmationDialog.setCancelable(false);
    deleteConfirmationDialog.setTitle("Confirm delete:");
    deleteConfirmationDialog.setContentView(R.layout.delete_comfirmation);
    deleteConfirmationDialog.findViewById(R.id.delete_delete).setOnClickListener(v -> {
      deleteConfirmationDialog.dismiss();
      new Thread(() -> {
        if (findViewById(ONGOING_OPERATION_ID) != null)
          setViewVisibility(ONGOING_OPERATION_ID, View.VISIBLE);
        final int currentOperation = hasOperations + 1;
        hasOperations = currentOperation;
        ArrayList<File> selectedFiles = new ArrayList<>(currentSelectedFiles.size());
        selectedFiles.addAll(currentSelectedFiles);
        appState.change(appState.idle);
        for (int i = 0; i < selectedFiles.size(); i++)
          FileOperation.delete(selectedFiles.get(i));
        if (hasOperations == currentOperation)
          hasOperations = 0;
        if (findViewById(ONGOING_OPERATION_ID) != null)
          setViewVisibility(ONGOING_OPERATION_ID, View.GONE);
        executor.execute(() -> listItem(new File(filePath)));
      }).start();
    });
    deleteConfirmationDialog.findViewById(R.id.delete_cancel).setOnClickListener(v -> {
      deleteConfirmationDialog.dismiss();
      appState.change(appState.idle);
    });
  }

  private void setupCreateDirectoryDialog() {
    createDirectoryDialog = new Dialog(this, R.style.app_theme_dialog);
    createDirectoryDialog.setTitle("Create new folder");
    createDirectoryDialog.setContentView(R.layout.new_directory);
    createDirectoryDialog.findViewById(R.id.new_directory_cancel).setOnClickListener(v -> createDirectoryDialog.dismiss());
    createDirectoryDialog.findViewById(R.id.new_directory_create).setOnClickListener(v -> {
      String name = ((EditText) createDirectoryDialog.findViewById(R.id.new_directory_name)).getText().toString();
      new File(filePath, name).mkdirs();
      createDirectoryDialog.dismiss();
      executor.execute(() -> listItem(new File(filePath)));
    });
  }

  private void setupBitmaps() {
    width = Resources.getSystem().getDisplayMetrics().widthPixels;
    folderImage = BitmapFactory.decodeResource(getResources(),
      R.drawable.folder);
    fileImage = BitmapFactory.decodeResource(getResources(),
      R.drawable.file);
    archiveImage = BitmapFactory.decodeResource(getResources(),
      R.drawable.archive);
    audioImage = BitmapFactory.decodeResource(getResources(),
      R.drawable.audio);
    videoImage = BitmapFactory.decodeResource(getResources(),
      R.drawable.video);
    pictureImage = BitmapFactory.decodeResource(getResources(),
      R.drawable.picture);
    unknownImage = BitmapFactory.decodeResource(getResources(),
      R.drawable.unknown);
  }

  private void forEachItem(ForEachItemFunction forEachItemFunction) {
    View v;
    for (int i = ll.getChildCount() - 1; i >= 0; i--) {
      v = ll.getChildAt(i);
      if (v instanceof Item)
        forEachItemFunction.forEachItem((Item) v);
    }
  }

  private interface ForEachItemFunction {
    void forEachItem(Item item);
  }

  private class State {

    State current, idle, select, paste, openFile;

    private State(boolean ignored) {
    }

    State() {
      idle = new State(true) {
        @Override
        void start() {
          setViewVisibility(R.id.paste_operation, View.GONE);
          setViewVisibility(R.id.select_operation, View.GONE);
          setViewVisibility(R.id.quick_selection, View.GONE);
          setViewVisibility(R.id.single_select_operation, View.GONE);
          clearHighlight();
          currentSelectedFiles.clear();
        }
      };

      select = new State(true) {
        @Override
        void start() {
          setViewVisibility(R.id.paste_operation, View.GONE);
          setViewVisibility(R.id.select_operation, View.VISIBLE);
          setViewVisibility(R.id.quick_selection, View.VISIBLE);
          setViewVisibility(R.id.menu_list, View.GONE);
        }
      };

      paste = new State(true) {
        @Override
        void start() {
          setViewVisibility(R.id.select_operation, View.GONE);
          setViewVisibility(R.id.quick_selection, View.GONE);
          setViewVisibility(R.id.paste_operation, View.VISIBLE);
          setViewVisibility(R.id.single_select_operation, View.GONE);
        }
      };

      openFile = new State(true);
      current = idle;
    }

    void start() {
    }

    public void change(State state) {
      current = state;
      MainActivity.this.runOnUiThread(current::start);
    }
  }

  class Item extends LinearLayout {
    final File file;

    private Item(final ImageView imageView, final File file) {
      super(MainActivity.this);

      this.file = file;
      this.setClickable(true);
      this.setOnClickListener(shortTabOnButton);
      this.setOnLongClickListener(longPressOnButton);

      final TextView textView = new TextView(MainActivity.this);
      textView.setText(file.getName());
      textView.setTextColor(Color.WHITE);
      textView.setBackgroundColor(Color.TRANSPARENT);
      textView.setWidth(width - (width / 8));
      textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16); //material list title

      this.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
      runOnUiThread(() -> {
        this.addView(imageView);
        this.addView(textView);
        ll.addView(this);
      });
    }
  }

  private class ShortTabOnButton implements View.OnClickListener {
    @Override
    public void onClick(final View view) {
      final File file = ((Item) view).file;
      if (!checkPermission())
        return;

      if (file.isDirectory()) {
        if (appState.current == appState.select) {
          selectFiles(file, view);
        } else {
          executor.execute(() -> listItem(file));
        }
      }

      if (file.isFile()) {
        if (appState.current == appState.idle) {
          appState.change(appState.openFile);

          currentSelectedFiles.clear();
          currentSelectedFiles.add(file);

          openListDialog.setTitle(file.getName());
          openListDialog.show();

        } else if (appState.current == appState.select) {
          selectFiles(file, view);
        }
      }
    }
  }

  private class LongPressOnButton implements View.OnLongClickListener {
    @Override
    public boolean onLongClick(final View view) {
      if (fopen.isRequestDocument) return true;
      if (appState.current == appState.idle || appState.current == appState.select) {
        appState.change(appState.select);
        final File file = ((Item) view).file;
        selectFiles(file, view);
      }
      return true;
    }
  }
}
