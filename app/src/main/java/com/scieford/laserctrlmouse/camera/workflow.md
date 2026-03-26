# Camera2Helper 优化说明

## 概述
`Camera2Helper` 类用于执行摄像头相关的操作，支持两种模式：普通模式和检测模式。普通模式用于摄像头图像预览和屏幕范围检测，检测模式用于高速帧率下的激光点检测。

## 功能需求
1. **初始化阶段**：
   - 遍历所有摄像头，获取每个摄像头的性能参数（如支持的帧率、分辨率、ISO范围等），并在日志中输出。

2. **普通模式**：
   - 使用自动曝光模式，帧率设置为30fps。
   - 将摄像头数据返回给 `TextureView`，用于界面预览和屏幕范围检测。

3. **检测模式**：
   - 使用高速帧模式，帧率设置为120fps。
   - 手动控制摄像头参数：ISO设为100，曝光时间设置为5ms。
   - 将摄像头数据返回给 `SurfaceView`，不显示在界面上，避免受到 `TextureView` 的帧率限制。
   - 获取的图像用于检测激光点位置。

4. **模式切换**：
   - 在 `MainActivity.kt` 中设置一个开关，用于切换普通模式和检测模式。

## 流程图

```mermaid
graph TD
    A[初始化] --> B[遍历所有摄像头]
    B --> C[获取摄像头性能参数]
    C --> D[输出日志]
    D --> E{模式选择}
    E -->|普通模式| F[设置自动曝光模式]
    F --> G[帧率设置为30fps]
    G --> H[数据返回给TextureView]
    H --> I[界面预览和屏幕范围检测]
    E -->|检测模式| J[设置手动曝光模式]
    J --> K[帧率设置为120fps]
    K --> L[ISO设为100, 曝光时间5ms]
    L --> M[数据返回给SurfaceView]
    M --> N[检测激光点位置]
    E --> O[模式切换开关]
    O --> P[MainActivity中切换模式]
```



```mermaid
graph TD
    A[MainActivity.onCreate] --> B[setupContent]
    B --> C[HighSpeedCameraScreen]
    C --> D[DisposableEffect]
    D --> E[LifecycleEventObserver]
    E --> F[camera2Helper.openCameraWithSurfaceView]
    E --> G[camera2Helper.closeCamera]
    F --> H[openCameraWithSurfaceViewInternal]
    H -->|1| I[findBestHighSpeedMode]
    H -->|2| P[startBackgroundThread]
    H -->|3| J[openCamera]
    J --> K[createHighSpeedSession]
    J --> L[createNormalSession]
    K --> M[setRepeatingBurst]
    L --> N[setRepeatingRequest]
    M --> O[updateFrameProcessingStats]
    N --> O
    K --> R[getValidHandler]
    L --> R
    J --> S[closeCamera]
    S --> Q[stopBackgroundThread]
    C --> T[Switch]
    T --> F
    C --> U[Button]
    U --> F
    C --> V[FixedAspectSurfaceView]
    V --> F
    V --> G

    %% 方法功能说明
    A["MainActivity.onCreate\n初始化摄像头工具和激光点检测器"]
    B["setupContent\n设置UI内容，调用HighSpeedCameraScreen"]
    C["HighSpeedCameraScreen\n主UI组件，包含摄像头预览和模式切换"]
    D["DisposableEffect\n监听生命周期事件"]
    E["LifecycleEventObserver\n根据生命周期事件（如ON_RESUME和ON_PAUSE）打开或关闭摄像头"]
    F["camera2Helper.openCameraWithSurfaceView\n打开摄像头并绑定到SurfaceView"]
    G["camera2Helper.closeCamera\n关闭摄像头，释放资源"]
    H["openCameraWithSurfaceViewInternal\n内部实现方法，负责检查权限、启动后台线程、调整SurfaceView尺寸等"]
    I["findBestHighSpeedMode\n查找设备支持的最佳高速模式（摄像头ID、最大分辨率、最高帧率）"]
    J["openCamera\n打开摄像头设备，并根据是否使用高速模式创建不同的会话"]
    K["createHighSpeedSession\n创建高速模式会话，用于处理高帧率视频"]
    L["createNormalSession\n创建普通模式会话，用于处理普通帧率视频"]
    M["setRepeatingBurst\n在高速模式下设置重复的捕获请求"]
    N["setRepeatingRequest\n在普通模式下设置重复的捕获请求"]
    O["updateFrameProcessingStats\n更新帧率统计信息"]
    P["startBackgroundThread\n启动后台线程，用于处理摄像头操作"]
    Q["stopBackgroundThread\n停止后台线程"]
    R["getValidHandler\n确保获取有效的Handler，如果后台线程Handler不可用，则使用主线程Handler"]
    S["closeCamera\n关闭摄像头，释放资源"]
    T["Switch\n切换高速模式和激光点检测模式，触发重新打开摄像头"]
    U["Button\n手动重新启动摄像头"]
    V["FixedAspectSurfaceView\n自定义的SurfaceView，用于显示摄像头预览，并触发打开和关闭摄像头"]
```

