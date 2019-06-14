package org.exoplatform.clouddrive.onedrive;

import java.io.*;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.*;
import java.util.Calendar;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

import com.google.gson.JsonElement;
import com.microsoft.graph.serializer.AdditionalDataManager;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.graph.models.extensions.*;
import com.microsoft.graph.options.HeaderOption;
import com.microsoft.graph.options.QueryOption;
import com.microsoft.graph.requests.extensions.*;

import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.RefreshAccessException;
import org.exoplatform.clouddrive.oauth2.UserToken;
import org.exoplatform.clouddrive.utils.ChunkIterator;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

class Scopes {
  static final String FilesReadAll            = "https://graph.microsoft.com/Files.Read.All";

  static final String FilesRead               = "https://graph.microsoft.com/Files.Read";

  static final String FilesReadSelected       = "https://graph.microsoft.com/Files.Read.Selected";

  static final String FilesReadWriteSelected  = "https://graph.microsoft.com/Files.ReadWrite.Selected";

  static final String FilesReadWrite          = "https://graph.microsoft.com/Files.ReadWrite";

  static final String FilesReadWriteAll       = "https://graph.microsoft.com/Files.ReadWrite.All";

  static final String FilesReadWriteAppFolder = "https://graph.microsoft.com/Files.ReadWrite.AppFolder";

  static final String UserRead                = "https://graph.microsoft.com/User.Read";

  static final String UserReadWrite           = "https://graph.microsoft.com/User.ReadWrite";

  static final String offlineAccess           = "offline_access";

  static final String UserReadWriteAll        = "https://graph.microsoft.com/User.ReadWrite.All";
}



public class OneDriveAPI {
  private String rootId;

  private class OneDriveToken{
    // in millis
    private final static int LIFETIME = 3600 * 1000;
    private String refreshToken;
    private String accessToken;
    private long lastModifiedTime;
    public OneDriveToken(String accessToken, String refreshToken){
      this.updateToken(accessToken,refreshToken);
    }

    public synchronized String getAccessToken() throws RefreshAccessException {
      long currentTime = System.currentTimeMillis();
      if (currentTime >= lastModifiedTime + /*LIFETIME*/ + 2000_000) { // TODO use constant
        try {
          if (LOG.isDebugEnabled()) {
            LOG.debug("refreshToken = " + this.refreshToken);
          }
          OneDriveTokenResponse oneDriveTokenResponse = retrieveAccessTokenByRefreshToken(this.refreshToken);
          this.accessToken = oneDriveTokenResponse.getToken();
          this.lastModifiedTime = System.currentTimeMillis();
          String refreshToken = oneDriveTokenResponse.getRefreshToken();
//          storedToken.store(refreshToken);
          this.refreshToken = refreshToken;
        } catch (IOException e) {
          throw new RefreshAccessException("Error during token update",e);
        }
      }
      return accessToken;
    }

    public final synchronized void updateToken(String accessToken, String refreshToken) {
      this.accessToken = accessToken;
      this.refreshToken = refreshToken;
      this.lastModifiedTime = System.currentTimeMillis();
    }
  }

  protected static final Log        LOG = ExoLogger.getLogger(OneDriveAPI.class);

  private final OneDriveStoredToken storedToken;

  private final String              clientId;

  private final String              clientSecret;

  private final OneDriveToken oneDriveToken;
  private final HttpClient httpclient = HttpClients.createDefault();
  private OneDriveTokenResponse retrieveAccessToken(String clientId,
                                                    String clientSecret,
                                                    String code,
                                                    String refreshToken,
                                                    String grantType) throws IOException {
    HttpPost httppost = new HttpPost("https://login.microsoftonline.com/common/oauth2/v2.0/token");
    List<NameValuePair> params = new ArrayList<>(5);
    if (grantType.equals("refresh_token")) {
      params.add(new BasicNameValuePair("refresh_token", refreshToken));
    } else if (grantType.equals("authorization_code")) {
      params.add(new BasicNameValuePair("code", code));
    } else {
      return null;
    }
    params.add(new BasicNameValuePair("grant_type", grantType));
    params.add(new BasicNameValuePair("client_secret", clientSecret));
    params.add(new BasicNameValuePair("client_id", clientId));
    params.add(new BasicNameValuePair("scope", SCOPES));
    try {
      httppost.setEntity(new UrlEncodedFormEntity(params, "UTF-8"));
    } catch (UnsupportedEncodingException e) {
      // TODO can we work normally after this ex? Or we should throw the ex higher?
      LOG.warn("Unsupported encoding", e);
    }

    HttpResponse response = httpclient.execute(httppost);

    HttpEntity entity = response.getEntity();
    if (entity != null) {
      try (InputStream inputStream = entity.getContent()) {
        String responseBody = IOUtils.toString(inputStream, Charset.forName("UTF-8"));
        return gson.fromJson(responseBody, OneDriveTokenResponse.class);
      }
    } // TODO else: may be we should inform that expected REST didn't return what we need? In log or may by an exception?
    return null;
  }

  // TODO propose a new name: aquireAccessToken
  private OneDriveTokenResponse retrieveAccessTokenByCode(String code) throws IOException {
    return retrieveAccessToken(clientId, clientSecret, code, null, "authorization_code");
  }

  // TODO propose a new name: renewAccessToken
  private OneDriveTokenResponse retrieveAccessTokenByRefreshToken(String refreshToken) throws IOException {
    return retrieveAccessToken(clientId, clientSecret, null, refreshToken, "refresh_token");
  }

  public SharingLink createLink(String itemId) {
    return graphClient.me().drive().items(itemId).createLink("embed", null).buildRequest().post().link;
  }

  public final static String SCOPES = scopes();

  private static String scopes() {
    StringJoiner scopes = new StringJoiner(" ");
    scopes.add(Scopes.FilesReadWriteAll)
          .add(Scopes.FilesRead)
          .add(Scopes.FilesReadWrite)
          .add(Scopes.FilesReadAll)
//          .add(Scopes.FilesReadSelected)
//          .add(Scopes.UserReadWriteAll)
          .add(Scopes.UserRead)
//          .add(Scopes.UserReadWrite)
          .add(Scopes.offlineAccess);
//          .add(Scopes.FilesReadWriteAppFolder)
//          .add(Scopes.FilesReadWriteSelected);
    return scopes.toString();
  }

  private void initGraphClient() {
    this.graphClient = GraphServiceClient.builder().authenticationProvider(iHttpRequest -> {
      String accessToken = null;
      try {
        accessToken = getAccessToken();
      } catch (RefreshAccessException e) {
        // TODO
      }
      iHttpRequest.getHeaders().add(new HeaderOption("Authorization", "Bearer " + accessToken));
    }).buildClient();
  }

  OneDriveAPI(String clientId, String clientSecret, String authCode) throws IOException, CloudDriveException {
    this.clientId = clientId;
    this.clientSecret = clientSecret;

    // TODO can do in single line these two?
    OneDriveTokenResponse oneDriveTokenResponse = null;
    oneDriveTokenResponse = retrieveAccessTokenByCode(authCode);

    // TODO don't assign the instance variable until you sure you have a response
    this.storedToken = new OneDriveStoredToken();
    if (oneDriveTokenResponse != null) {
      this.storedToken.store(oneDriveTokenResponse.getToken(),
                             oneDriveTokenResponse.getRefreshToken(),
                             oneDriveTokenResponse.getExpires());

      this.oneDriveToken = new OneDriveToken(oneDriveTokenResponse.getToken(),oneDriveTokenResponse.getRefreshToken());

      initGraphClient();
    } else {
      throw new CloudDriveException("Unable to retrieve access token");
    }
  }

  OneDriveAPI(String clientId, String clientSecret, String accessToken, String refreshToken, long expirationTime)
      throws CloudDriveException,
      IOException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("one drive api by refresh token");
    }
    // TODO don't assign the instance variables until you sure you have a response
    this.storedToken = new OneDriveStoredToken();
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    OneDriveTokenResponse oneDriveTokenResponse = null;
    oneDriveTokenResponse = retrieveAccessTokenByRefreshToken(refreshToken);
    if (oneDriveTokenResponse != null) {
      this.storedToken.store(oneDriveTokenResponse.getRefreshToken());
      this.oneDriveToken = new OneDriveToken(oneDriveTokenResponse.getToken(),oneDriveTokenResponse.getRefreshToken());
      initGraphClient();
    } else {
      throw new CloudDriveException("Unable to retrieve access token");
    }
  }

  private String getAccessToken() throws RefreshAccessException {
    return oneDriveToken.getAccessToken();
  }

  public void updateToken(OneDriveStoredToken newToken) {
    try {
      this.oneDriveToken.updateToken(newToken.getAccessToken(),newToken.getRefreshToken());
      this.storedToken.merge(newToken);
    } catch (CloudDriveException e) {
      // TODO can we work normally after this ex? Need throw an ex may be?
      LOG.error("unnable to merge token", e);
    }
  }

  private final Gson          gson = new Gson();

  private IGraphServiceClient graphClient;

  public void removeFolder(String fileId) {
    graphClient.me().drive().items(fileId).buildRequest().delete();
  }

  public void removeFile(String fileId) {

    // TODO formatting
      graphClient.me().drive().items(fileId).buildRequest().delete();


  }

  public User getUser() {
    return graphClient.me().buildRequest().get();
  }

  public synchronized String getRootId(){
    if (this.rootId == null) {
      this.rootId = getRoot().id;
    }
    return rootId;
  }
  private DriveItem getRoot() {
    return graphClient.me().drive().root().buildRequest().get();
  }

  private DriveItem createFolderRequestWrapper(String parentId, DriveItem folder){
    JsonObject obj = new JsonParser().parse("{\n" +
            "  \"name\": \""+folder.name+"\",\n" +
            "  \"folder\": { },\n" +
            "  \"@microsoft.graph.conflictBehavior\": \"rename\"\n" +
            "}").getAsJsonObject();
    String id = graphClient.customRequest("/me/drive/items/"+parentId+"/children").buildRequest().post(obj).get("id").getAsString();
    return getItem(id);
  }

  public DriveItem createFolder(String parentId, String name, Calendar created) {
    if (parentId == null || parentId.isEmpty()) {
      parentId = getRootId();
    }
    DriveItem folder = new DriveItem();
    folder.name = name;
    folder.parentReference = new ItemReference();
    folder.fileSystemInfo = new FileSystemInfo();
    folder.fileSystemInfo.createdDateTime = created;
    folder.parentReference.id = parentId;
    folder.folder = new Folder();

//    return graphClient.me().drive().items(parentId).children().buildRequest().post(folder);
    return createFolderRequestWrapper(parentId,folder);
  }

  public DriveItem copyFile(String parentId, String fileName, String fileId) throws RefreshAccessException {
    try {
      String copiedFileId = copy(parentId, fileName, fileId);
      if (LOG.isDebugEnabled()) {
        LOG.debug("copiedFileId = " + copiedFileId);
      }
      return getItem(copiedFileId);
    } catch (IOException e) {
      // TODO can we work normally after this ex? Later syncs will fail obviously - need throw an ex from here?
     LOG.error("error while copying file",e);
    }
    return null;
  }

  public DriveItem copyFolder(String parentId, String name, String folderId) throws RefreshAccessException {
    return copyFile(parentId, name, folderId);
  }



  public DriveItem getItemByPath(String path)  {
    try {
      return  graphClient.me().drive().root().itemWithPath(URLEncoder.encode(path, "UTF-8")).buildRequest().get();
    } catch (UnsupportedEncodingException e) {
      // TODO throw ex here
     LOG.error("unable to get file",e);
     return null;
    }
  }

  public OneDriveStoredToken getStoredToken() {
    return storedToken;
  }

  // TODO new name: getResourceId()
  private String retrieveCopiedFileId(String location) throws IOException {
    HttpGet httpget = new HttpGet(location);
    HttpResponse response = httpclient.execute(httpget);
    HttpEntity entity = response.getEntity();
    if (entity != null) {
      try (InputStream inputStream = entity.getContent()) {
        String responseBody = IOUtils.toString(inputStream, Charset.forName("UTF-8"));
        JsonObject jsonObject = new JsonParser().parse(responseBody).getAsJsonObject();
        if (jsonObject.has("resourceId")) {
          return jsonObject.get("resourceId").getAsString();
        }else{
          try {
            Thread.sleep(1000);
          } catch (InterruptedException e) {
            e.printStackTrace();
          }
          if (LOG.isDebugEnabled()) {
            LOG.debug("has not resourceId, responseBody = " + responseBody);
          }
          return retrieveCopiedFileId(location);
        }
      }
    } // TODO else, is it an error state? throw ex here?
    return null;
  }


  public String copy(String parentId, String fileName, String fileId) throws IOException, RefreshAccessException {

//    {
//      "parentReference" : {
//        "id" : "parenId"
//    },
//      "name" : "fileName",
//      "microsoft.graph.conflictBehavior", "rename"
//    }

    String request =
            "{\n" +
                    "  \"parentReference\": {\n" +
                    "    \"id\": \"" + parentId + "\"\n" +
                    "  },\n" +
                    "  \"name\": \"" + fileName + "\"\n" +
                    "}";
//            "{\n" +
//            "      \"parentReference\" : {\n" +
//            "        \"id\" : \""+parentId+"\"\n" +
//            "    },\n" +
//            "      \"name\" : \""+fileName+"\",\n" +
//            "      \"microsoft.graph.conflictBehavior\", \"rename\"\n" +
//            "    }";


    HttpPost httppost = new HttpPost("https://graph.microsoft.com/v1.0/me/drive/items/" + fileId + "/copy");
    StringEntity stringEntity = new StringEntity(request, "UTF-8");
    httppost.setEntity(stringEntity);
    httppost.addHeader("Authorization", "Bearer " + getAccessToken());
    httppost.addHeader("Content-type", "application/json");
    HttpResponse response = httpclient.execute(httppost);
    String location = response.getHeaders("Location")[0].getValue();

    return retrieveCopiedFileId(location);
  }



  public IDriveItemCollectionPage getDriveItemCollectionPage(String folderId) {

    IDriveItemCollectionPage iDriveItemCollectionPage = null;
    if (folderId == null) {
      iDriveItemCollectionPage = graphClient.me().drive().root().children().buildRequest().get();
    } else {
      iDriveItemCollectionPage = graphClient.me().drive().items(folderId).children().buildRequest().get();
    }
    return iDriveItemCollectionPage;
  }

  private List<DriveItem> getFiles(String folderId) {

    IDriveItemCollectionPage iDriveItemCollectionPage = null;
    if (folderId == null) {
      iDriveItemCollectionPage = graphClient.me().drive().root().children().buildRequest().get();
    } else {
      iDriveItemCollectionPage = graphClient.me().drive().items(folderId).children().buildRequest().get();
    }
    List<DriveItem> driveItems = new ArrayList<>(iDriveItemCollectionPage.getCurrentPage());
    IDriveItemCollectionRequestBuilder nextPage = iDriveItemCollectionPage.getNextPage();
    while (nextPage != null) {
      IDriveItemCollectionPage nextPageCollection = nextPage.buildRequest().get();
      driveItems.addAll(nextPageCollection.getCurrentPage());
      nextPage = nextPageCollection.getNextPage();
    }
    return driveItems;
  }

  private FileSendResponse sendFile(String url, int startPosition, int contentLength, int size, byte[] data) throws IOException {
    URL obj = new URL(url);
    HttpsURLConnection con = (HttpsURLConnection) obj.openConnection();
    con.setRequestMethod("PUT");
    con.setRequestProperty("Content-Length", String.valueOf(contentLength));
    con.setRequestProperty("Content-Range", "bytes " + startPosition + "-" + (startPosition + contentLength - 1) + "/" + size);
    con.setDoOutput(true);
    OutputStream outputStream = con.getOutputStream();
    outputStream.write(data);

    // TODO try-finally to flush/close the streams?
    outputStream.flush();
    outputStream.close();

    FileSendResponse fileSendResponse = new FileSendResponse();
    fileSendResponse.responseMessage = con.getResponseMessage();
    fileSendResponse.responseCode = con.getResponseCode();

    BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
    String inputLine;
    StringBuffer response = new StringBuffer();

    while ((inputLine = in.readLine()) != null) {
      response.append(inputLine);
    }
    in.close(); // TODO try-with-resource?

    fileSendResponse.data = response.toString();
    return fileSendResponse;
  }

  private byte[] readAllBytes(InputStream inputStream) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int nRead;
    byte[] data = new byte[16384];

    while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
      buffer.write(data, 0, nRead);
    }

    return buffer.toByteArray();
  }

  private class FileSendResponse {

    int    responseCode;

    String responseMessage;

    String data;

  }

  // TODO new name: getCreatedDriveItem()
  private DriveItem retrieveDriveItemIfCreated(FileSendResponse fileSendResponse) {
    if (fileSendResponse.responseCode == 201) {
      JsonObject jsonDriveItem = new JsonParser().parse(fileSendResponse.data).getAsJsonObject();
      DriveItem createdFile = graphClient.me().drive().items(jsonDriveItem.get("id").getAsString()).buildRequest().get();
      return createdFile;
    }
    return null;
  }


  private String uploadUrlConflictRenameWrapper(String path, DriveItemUploadableProperties driveItemUploadableProperties) throws UnsupportedEncodingException {

    /*
      "item": {
    "@odata.type": "microsoft.graph.driveItemUploadableProperties",
    "@microsoft.graph.conflictBehavior": "rename",
    "name": "largefile.dat"
  }
     */




    JsonObject uploadFileRequestBody = new JsonParser().parse("  \"item\": {\n" +
            "    \"@odata.type\": \"microsoft.graph.driveItemUploadableProperties\",\n" +
            "    \"@microsoft.graph.conflictBehavior\": \"rename\",\n" +
            "    \"name\": \""+driveItemUploadableProperties.name+"\"\n" +
            "  }").getAsJsonObject();




//    JsonObject uploadFileRequestBody = new JsonParser().parse("{\n" +
//            "      \"@microsoft.graph.conflictBehavior\":\"rename\",\n" +
//            "            \"description\":\"" + driveItemUploadableProperties.description + "\",\n" +
//            "            \"fileSystemInfo\":{\n" +
//            "      \"@odata.type\":\"microsoft.graph.fileSystemInfo\"\n" +
////            "      \"lastModifiedDateTime\" : \""+driveItemUploadableProperties.fileSystemInfo.lastModifiedDateTime+"\",\n" +
////            "      \"createdDateTime\" : \""+driveItemUploadableProperties.fileSystemInfo.createdDateTime+"\"\n" +
//            "    },\n" +
//            "      \"name\":\"" + driveItemUploadableProperties.name + "\"\n" +
//            "    }").getAsJsonObject();


    return graphClient.customRequest("/me/drive/root:/" + URLEncoder.encode(path, "UTF-8") + ":/createUploadSession").buildRequest().post(uploadFileRequestBody).get("uploadUrl").getAsString();
  }

  //TODO new name: getUploadUrl()
  private String retrieveUploadUrl(String path, DriveItemUploadableProperties driveItemUploadableProperties) {
    try {

//      return uploadUrlConflictRenameWrapper(path,driveItemUploadableProperties);
      return graphClient.me()
                        .drive()
                        .root()
                        .itemWithPath(URLEncoder.encode(path, "UTF-8"))
                        .createUploadSession(driveItemUploadableProperties)
                        .buildRequest()
                        .post().uploadUrl;
    } catch (UnsupportedEncodingException e) {
      // TODO throw it
      LOG.error("unsupported encoding", e);
      return null;
    }
  }

  private DriveItemUploadableProperties prepareDriveItemUploadableProperties(String fileName,
                                                                             Calendar created,
                                                                             Calendar modified) {
    DriveItemUploadableProperties driveItemUploadableProperties = new DriveItemUploadableProperties();
    driveItemUploadableProperties.name = fileName;
    driveItemUploadableProperties.fileSystemInfo = new FileSystemInfo();
    driveItemUploadableProperties.fileSystemInfo.createdDateTime = created;
    driveItemUploadableProperties.fileSystemInfo.lastModifiedDateTime = modified;
    return driveItemUploadableProperties;
  }


  public ChangesIterator changes(String deltaToken) {
    return new ChangesIterator(deltaToken);
  }

  /**
   * @param isInsert indicates whether the file needs to be changed or added
   */
  private DriveItem insertUpdate(String path,
                                 String fileName,
                                 Calendar created,
                                 Calendar modified,
                                 String mimetype,
                                 InputStream inputStream,
                                 boolean isInsert) {
    DriveItemUploadableProperties driveItemUploadableProperties =
                                                                prepareDriveItemUploadableProperties(fileName, created, modified);
    String uploadUrl = retrieveUploadUrl(path, driveItemUploadableProperties);
    byte[] file;
    try {
      file = readAllBytes(inputStream);
    } catch (IOException e) {
      // TODO may be throw an ex?
      LOG.error("Unable to read all bytes from received inputstream", e);
      return null;
    }
    int bufferSize = 327680 * 40; // must be a multiple of 327680
    for (int i = 0; i < file.length / bufferSize + 1; i++) {
      int from = bufferSize * i;
      int to = bufferSize * (i + 1);
      if (to > file.length) {
        to = file.length;
      }
      // TODO looks like not efficient work with memory, why we need arrays at all, can InputStream work for us?
      byte[] fileSlice = Arrays.copyOfRange(file, from, to);
      FileSendResponse fileSendResponse = null;
      try {
        fileSendResponse = sendFile(uploadUrl, from, fileSlice.length, file.length, fileSlice);
      } catch (IOException e) {
        LOG.error("Cannot upload part of file. ", e);
        // TODO throw it?
        return null;
      }
      DriveItem driveItem = processEndOfFileUploadIfReached(fileSendResponse, isInsert);
      if (driveItem != null) {
        return driveItem;
      } // TODO is it an error case?
    }
    return null;
  }

  private DriveItem processEndOfFileUploadIfReached(FileSendResponse fileSendResponse, boolean isInsert) {
    DriveItem driveItem = null;
    if (isInsert) {
      driveItem = retrieveDriveItemIfCreated(fileSendResponse);
    } else {
      driveItem = retrieveDriveItemIfUpdated(fileSendResponse);
    }
    return driveItem;
  }

  //TODO new name: getUpdatedDriveItem()
  private DriveItem retrieveDriveItemIfUpdated(FileSendResponse fileSendResponse) {
    if (fileSendResponse.responseCode == 200) {
      JsonObject jsonDriveItem = new JsonParser().parse(fileSendResponse.data).getAsJsonObject();
      DriveItem updatedFile = graphClient.me().drive().items(jsonDriveItem.get("id").getAsString()).buildRequest().get();
      return updatedFile;
    } // TODO error here?
    return null;
  }

  public DriveItem insert(String path,
                          String fileName,
                          Calendar created,
                          Calendar modified,
                          String mimetype,
                          InputStream inputStream) {
    // TODO remove mimetype
    if (LOG.isDebugEnabled()) {
      LOG.debug("insert file");
    }
    return insertUpdate(path, fileName, created, modified, mimetype, inputStream, true);
  }

  public DriveItem updateFileContent(String path,
                                     String fileName,
                                     Calendar created,
                                     Calendar modified,
                                     String mimetype,
                                     InputStream inputStream) {

    return insertUpdate(path, fileName, created, modified, mimetype, inputStream, false);
  }


  public DriveItem updateFileWrapper(DriveItem item) {
    // TODO rewrite with JsonObject
    JsonObject updateFileRequestBody = new JsonParser().parse("  {\n" +
            "            \"parentReference\": {\n" +
            "            \"id\": \""+item.parentReference.id+"\"\n" +
            "        },\n" +
            "            \"name\": \""+item.name+"\",\n" +
            "            \"@microsoft.graph.conflictBehavior\" : \"rename\"\n" +
            "        }").getAsJsonObject();
    String updatedFileId = graphClient.customRequest("/me/drive/items/" + item.id).buildRequest().patch(updateFileRequestBody).get("id").getAsString();
    return getItem(updatedFileId);
  }
  
  public DriveItem updateFile(DriveItem driveItem) {
    return updateFileWrapper(driveItem);
//    return graphClient.me().drive().items(driveItem.id).buildRequest().patch(driveItem);
  }

  public DriveItem getItem(String itemId) {
    return graphClient.me().drive().items(itemId).buildRequest().get();
  }

  public IDriveItemDeltaCollectionPage delta(String deltaToken) {
    IDriveItemDeltaCollectionPage iDriveItemDeltaCollectionPage = null;
    if (deltaToken == null || deltaToken.isEmpty() || deltaToken.toUpperCase().trim().equals("ALL")) {
      iDriveItemDeltaCollectionPage = graphClient.me().drive().root().delta().buildRequest().get();
    } else {
      final QueryOption deltaTokenQuery = new QueryOption("token", deltaToken);
      iDriveItemDeltaCollectionPage = graphClient.me()
                                                 .drive()
                                                 .root()
                                                 .delta()
                                                 .buildRequest(Collections.singletonList(deltaTokenQuery))
                                                 .get();
    }
    return iDriveItemDeltaCollectionPage;
  }

  public ChildIterator getChildIterator(String folderId) {
    return new ChildIterator(folderId);
  }

  class ChildIterator extends ChunkIterator<DriveItem> {

    IDriveItemCollectionPage driveItemCollectionPage;

    ChildIterator(String folderId) {

      this.driveItemCollectionPage = getDriveItemCollectionPage(folderId);
      iter = nextChunk();

    }

    @Override
    protected Iterator<DriveItem> nextChunk() {
      List<DriveItem> driveItems = new ArrayList<>(driveItemCollectionPage.getCurrentPage());
      available(driveItems.size());
      IDriveItemCollectionRequestBuilder nextPage = driveItemCollectionPage.getNextPage();
      if (nextPage != null) {
        driveItemCollectionPage = nextPage.buildRequest().get();
      } else {
        driveItemCollectionPage = null;
      }
      return driveItems.iterator();
    }

    @Override
    protected boolean hasNextChunk() {
      return driveItemCollectionPage != null;
    }

  }

  private String extractDeltaToken(String deltaLink) {
    return deltaLink.substring(deltaLink.indexOf("=") + 1);
  }

  class ChangesIterator extends ChunkIterator<DriveItem> {

    private  IDriveItemDeltaCollectionPage deltaCollectionPage;
    private String deltaToken;

    ChangesIterator(String deltaToken) {
      this.deltaToken = deltaToken;
      this.deltaCollectionPage = delta(deltaToken);
      iter = nextChunk();
    }


    @Override
    protected Iterator<DriveItem> nextChunk() {
      if (LOG.isDebugEnabled()) {
        LOG.debug("ChangesIterator nextChunk()");
      }
      final List<DriveItem> changes = new ArrayList<>();
      IDriveItemDeltaCollectionRequestBuilder nextPage;
      changes.addAll(deltaCollectionPage.getCurrentPage());
      nextPage = deltaCollectionPage.getNextPage();
      if (nextPage == null) {
        deltaToken = extractDeltaToken(deltaCollectionPage.deltaLink());
        deltaCollectionPage = null;
      } else {
        deltaCollectionPage = nextPage.buildRequest().get();
      }

      if (LOG.isDebugEnabled()) {
        LOG.debug("ChangesIterator nextChunk()   available " + changes.size());
      }
      available(changes.size());
      return changes.iterator();
    }

    @Override
    protected boolean hasNextChunk()
    { // TODO format
      return deltaCollectionPage!=null;
    }

    String getDeltaToken() {
      return deltaToken;
    }
  }

  class OneDriveStoredToken extends UserToken {
    public void store(String refreshToken) throws CloudDriveException {
      this.store("",refreshToken,0);
    }
  }

}
