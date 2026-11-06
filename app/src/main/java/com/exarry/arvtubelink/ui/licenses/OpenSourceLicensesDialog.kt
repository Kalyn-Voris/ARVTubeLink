package com.exarry.arvtubelink.ui.licenses

import androidx.compose.animation.AnimatedVisibility
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.exarry.arvtubelink.R

/** 列表/详情切换的横向滑入时长，与 Material 的「进入下一层」节奏对齐。 */
private const val NavAnimMs = 220

/**
 * 开源许可全屏页（v1.3.x）。
 *
 * ## 结构
 * 单 [Dialog] + 两级内部状态（列表 ⇄ 详情），**不嵌套第二个 Dialog、不引入导航库**：
 *
 *  - 两级页面同处一个全屏 Dialog 内，用 `nullable OpenSourceLibrary` 表示层级：
 *    `null` = 列表页，非 null = 该库的详情页；
 *  - 用 [AnimatedVisibility] 做横向推入/推出，模拟二级页面的层级感；
 *  - Back 键行为由这一层统一接管：详情页时先退回列表，列表页时才关窗。
 *
 * 为什么不用 Navigation-Compose：为两个页面引入一个导航图 + 依赖，收益远低于成本；
 * 本项目全程是「单 Activity + 状态提升」，这个弹窗保持同样的思路。
 *
 * ## 为什么是 Dialog 而不是切 Tab / 新 Activity
 * 许可页是「短暂查阅」场景，看完即走。做成 Tab 会永久占用一个底部导航位；
 * 做成 Activity 则要新增 manifest 条目并处理主题/语言重建。全屏 Dialog 最轻。
 *
 * @param onDismiss 关闭回调（返回键 / 关闭按钮 / 点外部均触发）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSourceLicensesDialog(onDismiss: () -> Unit) {
    // 两级页面的状态用两个变量表达，**刻意拆开**：
    //  - [detailVisible] 控制「详情层是否可见」；
    //  - [detailContent] 保存「详情层要显示哪个库」，**只在点击时写入**，收起时不清理。
    //
    // 为什么必须拆：AnimatedVisibility 的退出动画期间 visible 已变 false，但内容仍需
    // 完整渲染完那 220ms。若只用一个 `OpenSourceLibrary?` 同时表达两件事，退出时它变
    // null，内容立即塌成空白 → 滑出动画会变成「白板滑走」。
    // 而如果用 `if (x != null) held = x` 之类的写法在**组合期**回填另一个 state，
    // 又属于 Compose 明确不推荐的副作用。
    // 拆成两个变量后，写入点全部在手势回调里（onSelect / 返回），组合期零副作用。
    var detailVisible by remember { mutableStateOf(false) }
    var detailContent by remember { mutableStateOf<OpenSourceLibrary?>(null) }

    // 返回键分层接管：详情页时「返回」= 退回列表，列表页时才真正关窗。
    //
    // ⚠ 这里踩过两个坑，都已在下方修掉：
    //  1. 原实现 `BackHandler(enabled = detailVisible)`：列表页时 enabled=false，
    //     系统返回键于是落到 Dialog 自身的 cancel 逻辑 —— 直接关掉整个全屏窗，
    //     而做不到「详情页返回只退一层」。现在改成**始终 enabled = true**，
    //     由内部自行分流。
    //  2. Dialog 的 `dismissOnBackPress` 默认 true，它会在 window 层先处理返回键
    //     （触发 onDismissRequest），BackHandler 可能根本收不到。因此
    //     `onDismissRequest` 也必须写同一套分流逻辑——两条路径谁先命中都正确，
    //     且同一事件只可能走其中一条（被消费后不再下发）。
    BackHandler(enabled = true) {
        if (detailVisible) {
            // 只退一层：回到列表。**不清 detailContent**——AnimatedVisibility 的
            // 退出动画还要用它渲染 220ms，清掉会滑出一块空白。
            detailVisible = false
        } else {
            onDismiss()
        }
    }

    Dialog(
        // 返回键的第二条路径（见上方说明）：Dialog 可取消时，返回键在 window 层
        // 被吃掉并回调到这里。必须与 BackHandler 分流逻辑保持一致。
        onDismissRequest = {
            if (detailVisible) detailVisible = false else onDismiss()
        },
        // 全屏三件套：不用平台默认宽度（否则会留一圈 20dp 边距、内容被压窄），
        // 也不画系统背景 → 由下方 Surface 铺满并填充安全区。
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false, // 全屏页没有「外部」可点，关掉避免误触
            decorFitsSystemWindows = false, // 自己用 Scaffold 的 insets 处理，避免双重留白
        ),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                // 详情页时标题换成库名，返回箭头从「关闭」变「退回列表」——
                // 同一个按钮位承担两种语义，符合二级页面的通行约定
                TopAppBar(
                    title = {
                        Text(
                            text = if (detailVisible) {
                                detailContent?.name ?: stringResource(R.string.oss_title)
                            } else {
                                stringResource(R.string.oss_title)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (detailVisible) detailVisible = false else onDismiss()
                            }
                        ) {
                            Icon(
                                imageVector = if (detailVisible) {
                                    Icons.AutoMirrored.Filled.ArrowBack
                                } else {
                                    Icons.Filled.Close
                                },
                                contentDescription = stringResource(
                                    if (detailVisible) R.string.oss_back else R.string.oss_close
                                ),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                )
            },
        ) { innerPadding ->
            // 两级页面叠放：列表始终在组合树里（保留滚动位置），详情层覆盖其上。
            // 用 AnimatedVisibility 而非 if：退出动画期间列表仍在，不会先闪白。
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                LicenseList(
                    onSelect = { library ->
                        // 写入点全部在事件回调里：先定内容，再置可见
                        detailContent = library
                        detailVisible = true
                    }
                )

                // 详情层：content 只在点击时写入、收起时不清理，
                // 因此退出动画播放全程都有完整内容可渲染（不会滑走一块白板）。
                //
                // ★ zIndex(1f)：列表在 z=0、详情在 z=1，保证详情画在列表**之上**。
                //   同层 Box 默认按组合顺序绘制，这里显式抬高是为了让层级意图明确，
                //   避免将来有人在下方再插一层内容时把详情压到列表底下。
                AnimatedVisibility(
                    visible = detailVisible,
                    enter = slideInHorizontally(
                        animationSpec = tween(NavAnimMs)
                    ) { it } + fadeIn(tween(NavAnimMs)),
                    exit = slideOutHorizontally(
                        animationSpec = tween(NavAnimMs)
                    ) { it } + fadeOut(tween(NavAnimMs)),
                    modifier = Modifier.zIndex(1f),
                ) {
                    detailContent?.let { LicenseDetail(library = it) }
                }
            }
        }
    }
}

/**
 * 一级列表：分「核心平台 / 开源库」两段展示「库名 + 协议名」。
 *
 * 为什么是分区而非一个拉平列表：这两类条目性质不同——核心平台是专有 SDK 或仅接口
 * 互操作的第三方服务，开源库是真正随包分发的 Apache-2.0 代码。混排会让用户误以为
 * 华为 AR Engine 也是开源软件。分区顺序按 [licenseSections] 的声明顺序（核心平台在前），
 * 段内保持手写顺序，不再做字母序重排——「AndroidX → Kotlin → OkHttp → Gson」这种
 * 由底层到业务层的排列比字母序更有信息量。
 *
 * 行内信息取舍：不展示 version/url，只留库名与协议名两列——列表页的职责是
 * 「快速找到某个库」，详细信息都在详情页。多塞两列会让窄屏上的库名被迫截断。
 */
@Composable
private fun LicenseList(onSelect: (OpenSourceLibrary) -> Unit) {
    val sections = remember { licenseSections }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.oss_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        sections.forEach { section ->
            // 分类标题：Material 3 的 titleSmall + 加粗 + onSurfaceVariant（略暗），
            // 与内容行（bodyLarge / onSurface）形成明确层级，但不抢主标题。
            item(key = "section-${section.titleRes}") {
                Text(
                    text = stringResource(section.titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = 16.dp,
                        bottom = 4.dp,
                    ),
                )
            }
            items(section.libraries, key = { it.name }) { library ->
                LicenseRow(library = library, onClick = { onSelect(library) })
                HorizontalDivider(
                    modifier = Modifier.padding(start = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
        item {
            Text(
                text = stringResource(R.string.oss_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/** 列表单行：库名（主）/ 协议名（次），右侧箭头暗示可点。 */
@Composable
private fun LicenseRow(library: OpenSourceLibrary, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = library.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = library.licenseName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null, // 装饰性：整行可点，语义由 Surface(onClick) 承担
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 二级详情：协议头部信息（库名/版本/协议/来源）+ 全文。
 *
 * 全文用等宽字体 + 可滚动：许可文本是有缩进层级的法律条文，比例字体会让
 * 条款编号与缩进错位、极难扫读。
 */
@Composable
private fun LicenseDetail(library: OpenSourceLibrary) {
    val uriHandler = LocalUriHandler.current

    // ★ 不透明底色：本页与列表页同处一个 Box 叠放，两者都在组合/绘制中。
    //   根节点若只是个裸 Column（透明），列表的库名与这里的协议全文会直接叠印在
    //   一起 —— 就是用户截图里那团糊字。Surface 的 color 会实际填充背景像素，
    //   把下层彻底盖住；同时它自带 clip + 语义，比手写 background() 更稳。
    //   注意用 `Modifier.fillMaxSize()` 让 Surface 铺满整个内容区：
    //   如果只包住内容高度，滑入动画过程中右侧未覆盖区域仍会漏出列表文字。
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // ---- 头部信息卡 ----
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = library.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    InfoLine(stringResource(R.string.oss_label_version), library.version)
                    InfoLine(stringResource(R.string.oss_label_license), library.licenseName)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = library.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { runCatching { uriHandler.openUri(library.url) } }
                        ) {
                            Icon(
                                // OpenInNew 在 material-icons-extended 里，本工程只依赖 core 集
                                // （见 libs.versions.toml 注释：「m3 1.4.0 不传递暴露 icons」）。
                                // 为这一个图标引入整个 extended 包（约 1.5MB）不划算，
                                // 用 core 里语义最接近的 Share（对外跳转）代替。
                                imageVector = Icons.Filled.Share,
                                contentDescription = stringResource(R.string.oss_open_link),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(library.descriptionRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            // ---- 协议全文 ----
            Text(
                text = library.licenseText,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 18.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 详情页头部的一行「键：值」。 */
@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}
