# AzurelandPlugin

支持 Paper 和 Folia 1.21.8 或更高版本，使用 Paper 1.21.8 API 编译，构建需要 Java 21 或更高版本和 Maven。两种服务器共用异步和实体调度器，不提供 Bukkit/Spigot 调度兼容层。服务器运行所需的 Java 版本以对应服务器版本为准。

## 使用

1. 执行 `mvn -Dmaven.test.skip=true package`。
2. 将 `target/azureland-plugin-1.0.0.jar` 放入服务器的 `plugins` 目录。
3. 重启服务器，主手拿着物品执行 `/quickfix`。

权限：`azureland.quickfix`，默认仅 OP 拥有。可通过服务器权限插件授予普通玩家。

每次成功修复默认消耗 30 金币，通过 [Vault API](https://github.com/MilkBowl/VaultAPI) 使用服务器经济插件的默认货币。配置 `quickfix.cost` 可修改费用，支持非负小数，设为 `0` 时免费；修改后执行 `/helpme reload` 或重启生效。OP 也照常扣费，不设默认免单权限。

收费需要 Vault 兼容桥接插件和注册了 Vault Economy 服务的经济插件；Vault 本身不提供余额。Folia 应使用 [VaultUnlocked](https://github.com/TheNewEconomy/VaultUnlocked) 以及明确支持 Folia 的经济插件，扣费在执行命令的玩家区域线程进行，经济服务自身也必须支持这种调用。未安装桥接/经济服务或余额不足时不修复、不扣费，不影响 AI 助手；空手、无需修复和参数错误同样不扣费。费用无效时仅暂停快速修复并记录警告，免费修复不依赖经济插件。

命令将物品损耗（Damage / durability）和铁砧累计修复惩罚（RepairCost）归零，恢复满耐久。保留物品数量、附魔、名称、描述和其他元数据。只有修复惩罚的物品（例如附魔书）也可处理。

空手、无需修复、额外参数和控制台执行均有对应提示；不处理副手或整个背包。

使用 Bukkit 的 `Damageable#setDamage(0)` 和 `Repairable#setRepairCost(0)` 实现，不依赖 NMS。此处的“移除”指清零损耗和修复惩罚；底层是否省略零值标签由服务器实现决定。

API 参考：[Damageable](https://hub.spigotmc.org/javadocs/bukkit/org/bukkit/inventory/meta/Damageable.html)、[Repairable](https://hub.spigotmc.org/javadocs/bukkit/org/bukkit/inventory/meta/Repairable.html)。

## 随身工作站

以下命令直接打开原生工作站界面，无需放置对应方块，只能由玩家执行，且不接受参数。

| 命令 | 界面 | 权限 |
| --- | --- | --- |
| `/craft` | 工作台 | `azureland.craft` |
| `/anvil` | 铁砧 | `azureland.anvil` |
| `/smithing` | 锻造台 | `azureland.smithing` |
| `/grindstone` | 砂轮 | `azureland.grindstone` |
| `/loom` | 织布机 | `azureland.loom` |
| `/cartography` | 制图台 | `azureland.cartography` |
| `/stonecutter` | 切石机 | `azureland.stonecutter` |

这些权限默认所有玩家拥有，可通过权限插件分别控制。打开界面不收取金币，合成材料和铁砧经验费用按原版规则处理。

使用 [Paper HumanEntity 工作站 API](https://jd.papermc.io/paper/1.20.4/org/bukkit/entity/HumanEntity.html) 打开界面，不依赖 NMS。

## AI 助手

启动后生成 `plugins/AzurelandPlugin/config.yml`。填写 `ai.api-key`，修改模型等配置后执行 `/helpme reload`。

配置当前版本为 `config-version: 4`，不会随功能修改自行递增。启动与 `/helpme reload` 时，版本低于 4 的配置（没有版本号按 0 处理）会自动补齐缺失项并写入版本 4，保留仍适用的已有值和自定义配置，包括自定义系统提示词；等于或高于 4 时不改写配置。升级到版本 4 会移除旧的 `global-daily-token-limit` 和 `player-daily-token-limit`，补齐 credits 费率和每日额度，不把旧 token 额度换算为 credits。插件启动时直接删除旧 `token-usage.yml` 及其临时文件，不迁移旧用量，不删除历史会话。版本 2 升级后新增 `quickfix.cost: 30`，快速修复开始收费。

- `/helpme`：打开六行箱子会话主菜单。采用黑色玻璃边框、顶部会话总数、四行会话卡片和底部分页栏；第二至第五行左右两格为黑色玻璃，中间七格可放置会话，每页有 28 个列表格。青蓝玻璃只放在最后一行的功能按钮之间，会话操作和删除确认子菜单不放青蓝玻璃。会话按创建顺序排列，“创建新会话”紧跟最后一个会话占下一格，排满时顺延到下一页。没有会话时，列表第一格显示“创建你的第一个会话”。底部提供上一页、每日额度、关闭、我的重置卡和下一页，左上角可查看命令帮助。
- 菜单操作：左键会话卡片打开三行箱子操作子菜单，提供“提问”“查看历史”“删除会话”和返回列表；主菜单不再使用右键或 Shift 快捷操作，也没有选中状态、选中高亮或“当前会话”功能。删除进入箱子确认菜单，确认后移除会话名称和全部历史，取消返回该会话的操作菜单；删除不可恢复，不改变 credits 用量、赠送额度或冷却。问题或会话清理仍在处理时，需要等待完成后再删除，以免旧请求重新保存已删除的会话。
- 子菜单中的提问和历史使用原生 dialog，点击“返回会话操作”回到对应子菜单，再返回原来的主菜单页；创建名称使用原生 dialog，创建成功后返回新会话所在的主菜单页。问题输入长度受 `ai.max-question-length` 限制，提交时使用当前配置，并沿用提问权限、冷却、并发和每日额度限制。原生 dialog 要求客户端 Minecraft Java 1.21.6 或更高版本。
- `/helpme create [会话名]`：不加名称时打开创建 dialog；填写名称后创建空会话并返回主菜单。名称为 1–32 个字符，不能包含空白或控制字符，区分大小写；每位玩家的名称和上下文互相隔离，不能重复创建同名会话。
- `/helpme history [会话名]`：填写名称时在原生 dialog 中查看该会话历史，不加名称时打开主菜单，通过会话操作子菜单查看历史，支持会话名 Tab 补全。按保存顺序每页展示一轮对话，上方是“你的问题”，下方是“AI 回复”，标注当前页码和已保存轮数；同一轮的问题和回复在同一个 dialog 的富文本正文中完整显示，保留 Markdown 格式、颜色、网页链接、原文段落和显式换行。正文按默认字体的像素宽度换行，再在行尾补空白，模拟左对齐。长消息可用鼠标滚轮或右侧原生滚动条手动滚动阅读，不沿用聊天回复的截断限制。当前版本原生 dialog 的富文本正文固定居中，没有左对齐参数；插件使用 Minecraft 26.3 默认字体的字宽和粗体偏移计算显示行，分别按中文字形、英文字母、全角和半角标点、不同空白字符及粗体偏移测量宽度；按 Unicode 断行规则处理混排，避免句号、右括号等闭合标点出现在行首，并避免将列表符号单独放一行。Tab 在阅读界面显示为四个普通空格。dialog 外框宽度为 360 像素；按 26.3 客户端左右各 4 像素内边距，正文换行与补白均使用实际 352 像素宽度，防止补白触发客户端二次换行，再通过普通空格和带粗体的零宽字符进行整像素补白，使可见文字靠左，同时保留样式和链接。消息沿用默认字体，不强制切换为 Unicode 字体。此处理只用于界面，不改写保存的问答或影响 token 计费。使用自定义字体资源包、强制 Unicode 字体或不同字形时，模拟对齐可能有偏差。支持上一轮对话、下一轮对话、跳转最早或最新对话、刷新历史和返回会话操作。查看不请求 AI、不消耗 credits，也不触发提问冷却。切换对话时查看同一份快照，刷新后显示最新已保存问答；未完成的问答在请求完成并成功保存后才会出现在历史中。手动或超限自动清空上下文也会移除对应的可查看历史。
- `/helpme <问题>`：打开本次提问的目标会话 dialog，点击会话名称后提交问题；没有会话时先引导创建，不发起 AI 请求。此选择只用于本次问题，不保存选中状态。也可从箱子操作子菜单点击“提问”输入问题。回答均在聊天栏仅发给提问玩家，并标注会话名，自动携带该会话的历史问答；已提交的请求固定使用提交时的会话。旧的 `/helpme select` 命令已移除，执行时会提示使用主菜单。
- `/helpme help`：显示命令帮助，控制台也可执行。
- `/helpme status`：查询自己的个人和全服每日 credits 已用、上限及剩余额度，并显示今日赠送额度、每 token 费率、上下文上限和每日重置时间。已用额度包含正在处理请求的预留 credits，完成后按实际用量结算；查询不消耗额度，也不触发提问冷却，AI 未配置 API Key 或停用时仍可查询。
- `/helpme status 玩家名`：管理员查询指定玩家的限额，支持已加入过服务器的离线玩家和控制台执行。
- `/helpme give 玩家名 credits`：管理员给指定玩家增加今日个人 credits 额度，数量必须是正整数。例如 `/helpme give Steve 50000` 将今日个人上限增加 50,000 credits。支持已加入过服务器的离线玩家和控制台执行；赠送可以累加，按配置时区午夜重置后失效，不抵扣已用量，也不增加全服每日上限。AI 未配置 API Key 或停用时仍可赠送。
- `/helpme reset <玩家名|all>`：管理员清零指定玩家的今日个人 credits 已用量；`all` 同时清零今日全服和所有玩家的已用量，包括离线玩家。指定玩家重置不改变全服用量，所有重置均保留今日赠送额度、会话和提问冷却，不改变配置中的额度上限或过去日期的记录。支持已加入过服务器的离线玩家和控制台执行，`all` 不区分大小写，支持 Tab 补全在线玩家及 `all`。按配置的每日重置时区确定“今日”，结果立即持久化；重置前已预留的请求结算时不会重新计入已重置的用量，也不会从零扣出负数，新预留请求正常计费。AI 未配置 API Key 或停用时仍可重置。
- `/helpme myresets`：打开三行个人重置卡箱子菜单，显示剩余卡数、今日个人用量、上限和剩余额度，提供使用按钮、返回会话列表和关闭按钮。主菜单原“刷新列表”按钮改为打开此菜单。每次使用消耗 1 张卡并清零自己的今日 credits 已用量，保留赠送额度、全服用量、会话和冷却；没有卡或今日用量为 0 时不消耗卡。卡数和清零用量在同一次原子写入中保存，重置前已预留的请求不会重新计入已重置的个人用量。默认所有玩家可用，控制台不能打开菜单。
- `/helpme reset give <玩家名|all> <数量>`：管理员给指定玩家增加重置卡，数量为正整数；`all` 给所有已加入过服务器的玩家各发放该数量，包括离线玩家，不预发给未来加入的玩家。支持控制台，支持 `give`、在线玩家和 `all` 的 Tab 补全。卡可累加，按 UUID 保存到 `credit-usage.yml` 的 `reset-cards` 字段，跨天、重启和配置重载均保留，管理员直接重置额度也不清除卡。发卡不直接改变 credits 用量或上限，不要求 AI 已启用或配置 API Key。
- `/helpme clear <会话名>`：清空自己的指定会话上下文，保留会话名称，不清零每日 credits 用量、赠送额度或提问冷却。正在提问或清空时需等待操作完成后重试；AI 未配置或停用时也可使用。名称必须填写，支持 Tab 补全自己的会话名；不再接受玩家名或全服清空参数，`all` 仅作为普通会话名处理。此命令只能由玩家执行。
- `/helpme reload`：热重载配置（控制台也可执行）。正在进行的请求使用提交时的配置。
- `azureland.helpme`：默认所有玩家可用，允许提问、创建和删除自己的会话、查看自己的会话历史。
- `azureland.helpme.status`：默认所有玩家可用，可查询自己的限额。
- `azureland.helpme.status.others`：默认仅 OP，可查询其他玩家的限额。
- `azureland.helpme.give`：默认仅 OP，可给玩家增加今日 credits 额度。
- `azureland.helpme.reset`：默认仅 OP，可重置指定玩家或全服的今日 credits 用量。
- `azureland.helpme.myresets`：默认所有玩家可用，可打开重置菜单并使用自己的重置卡。
- `azureland.helpme.reset.give`：默认仅 OP，可给指定玩家或全服发放重置卡。
- `azureland.helpme.clear`：默认所有玩家可用，只能清空自己的指定会话上下文。
- `azureland.helpme.reload`：默认 OP 可用。

```yaml
config-version: 4

quickfix:
  cost: 30

ai:
  enabled: true
  provider: openai-responses
  base-url: https://api.openai.com/v1
  api-key: '填写你的 API Key'
  model: gpt-5-mini
  reasoning-effort: medium
  web-search:
    enabled: true
  plugin-list:
    enabled: true
  system-prompt: |-
    你是 Azureland 服务器的 AI 助手，请用简洁中文回答，可以使用 Markdown 排版。
    可适度用 <color:yellow>重点</color> 或 <color:#55FF55>文字</color> 设置文字颜色，颜色标签必须闭合；不要使用其他交互标签。
    服务器规则：在这里填写实际规则、常见问题和可用命令。
  timeout-seconds: 60
  max-output-tokens: 4096
  max-question-length: 1000
  cooldown-seconds: 10
  max-concurrent-requests: 4
  global-daily-credit-limit: 5000000
  player-daily-credit-limit: 100000
  credits-per-token:
    input: 4
    output: 12
    cached-input: 1
  context-token-limit: 100000
  daily-reset-timezone: Asia/Shanghai
```

`ai.provider` 用于选择实际请求协议，切换时同时填写对应的 `base-url`、`api-key` 和 `model`。根地址必须包含 API 版本路径，不包含下表中的具体请求路径；第三方网关选择它实际兼容的格式。

| provider | 原生协议 / 自动追加的路径 | 官方 API 根地址 | 鉴权 |
| --- | --- | --- | --- |
| `openai` | Chat Completions：`/chat/completions` | `https://api.openai.com/v1` | Bearer |
| `openai-responses` | Responses：`/responses` | `https://api.openai.com/v1` | Bearer |
| `anthropic` | Messages：`/messages` | `https://api.anthropic.com/v1` | `x-api-key`，版本头 `2023-06-01` |
| `gemini` | generateContent：`/models/{model}:generateContent` | `https://generativelanguage.googleapis.com/v1beta` | `x-goog-api-key` |

新配置默认 `openai-responses`，沿用此前的 Responses 行为。**旧配置中的 `provider: openai` 现在明确表示 Chat Completions；希望继续使用原接口时，请手动改为 `openai-responses` 并执行 `/helpme reload`。** 旧值 `openai-compatible` 作为 `openai-responses` 别名继续读取；不自动改写已有值，`config-version` 保持 4。Gemini 模型填写模型 ID，也可带 `models/` 前缀，插件会移除重复前缀。已有命名会话可以继续使用，历史问答按目标协议转换。

`reasoning-effort: ''` 会省略思考参数，使用模型默认行为。非空时：

- `openai` 映射到 `reasoning_effort`；`openai-responses` 映射到 `reasoning.effort`。
- `anthropic` 启用 `thinking.type: adaptive` 并设置 `output_config.effort`，模型必须支持自适应思考和 effort；不支持的模型请留空。
- `gemini` 的文本值（如 `low`、`medium`、`high`）映射到 `generationConfig.thinkingConfig.thinkingLevel`；数字字符串（如 `'1024'`、`'-1'`）映射到 `thinkingBudget`，`-1` 表示动态预算。Gemini 2.5 使用预算或留空，不接受 Gemini 3 的 thinkingLevel；各模型支持的级别、预算范围不同，以官方文档为准。

输出上限分别映射到 `max_completion_tokens`、`max_output_tokens`、`max_tokens`、`generationConfig.maxOutputTokens`；计费包含接口报告的思考 token。不支持某项参数的模型或网关需调整相应配置，不会静默切换协议或模型。

代码中四种格式统一由 `AiFormats` 构建请求、构建计数载荷、解析返回文本与用量、处理引用及工具续接；`AiClient` 负责 HTTP 鉴权、超时、状态码处理和计数请求。`HelpMeCommand` 使用同一组返回数据和续接入口处理会话、额度及插件列表调用，Responses 与其他格式采用相同组织方式。

网络请求和会话、用量文件读写在异步调度器中运行，玩家回复在对应玩家的实体调度器中发送，跟随玩家跨区域移动。请求准入、冷却和全服并发计数通过同步访问保证多区域线程之间的一致性，配置通过不可变快照发布。设有超时、玩家冷却、同一玩家单请求和全服并发限制，长回答会分段显示，最多 80 段。插件不执行 AI 建议的命令，也不读取世界、玩家背包或其他插件配置；要让助手了解服务器规则，请写入系统提示词。问题、历史和系统提示词发送到配置的提供商，工具调用时还会发送插件列表，API Key 不写入日志或聊天。

接口参考：[OpenAI Chat Completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)、[OpenAI Responses](https://developers.openai.com/api/docs/guides/migrate-to-responses)、[Anthropic Messages](https://platform.claude.com/docs/en/api/messages/create)、[Gemini generateContent](https://ai.google.dev/api/generate-content)。

## Markdown 显示

AI 回答使用 [CommonMark](https://github.com/commonmark/commonmark-java) 解析，再转换为 Minecraft Adventure 聊天组件。支持标题、粗体、斜体、删除线、有序/无序列表、引用、分隔线、行内代码、代码块、表格和链接；转义符和代码中的 Markdown 不会被当作样式解析。

标题使用青色粗体，代码使用黄色，引用使用灰色；代码保留换行但不做语法高亮，表格逐行显示，不保证聊天窗口中列宽对齐。图片显示为带链接的替代文字，不加载图片；HTML 仅按文字显示。仅 HTTP/HTTPS 链接可点击，不执行命令或其他协议。已有搜索引用仍可点击。

长回答在解析后按每段最多 200 个字符分段，最多 80 段，分段保留文字样式和链接。会话仍保存原始回答，不改变 token 计费或上下文逻辑。已有自定义系统提示词不会被覆盖，如原提示词要求纯文本，可自行调整。解析库随插件打包并重定位，无需额外安装依赖插件。

AI 可以使用颜色标签自定义文字颜色：`<color:yellow>重点</color>`、`<color:#55FF55>绿色文字</color>`，或简写 `<red>警告</red>`。支持 Minecraft 的 16 种命名颜色和六位 RGB 十六进制颜色，颜色名不区分大小写；支持嵌套，闭合标签恢复外层颜色，`<reset>` 恢复默认颜色但不取消 Markdown 样式。颜色可与粗体、列表、标题和链接同时使用，并覆盖默认文字颜色，不改变 `[AI]` 前缀。

仅解析上述颜色标签，不支持 MiniMessage 的其他标签、命令执行或自定义点击事件；代码中的标签和转义后的标签原样显示。未识别的标签作为文字显示。默认系统提示词已加入颜色写法说明，已有提示词不会被自动改写，管理员可将示例配置中的颜色说明加入 `ai.system-prompt` 后执行 `/helpme reload`。

## 联网搜索

`ai.web-search.enabled` 默认开启，按 provider 使用原生搜索接口：

| provider | 搜索参数 / 工具 | 使用要求 |
| --- | --- | --- |
| `openai` | `web_search_options: {}` | 所选 Chat Completions 模型/网关必须支持该参数；OpenAI 官方仅专用搜索模型支持，且每次请求都会搜索 |
| `openai-responses` | `web_search` | 模型和接口支持 Responses 原生搜索，由模型按需调用 |
| `anthropic` | `web_search_20250305`，每次请求最多 5 次搜索 | 模型和账号支持基础搜索；支持服务端 `pause_turn` 续接 |
| `gemini` | `googleSearch` | 模型和接口支持 Google Search grounding；同时启用插件函数时使用 `includeServerSideToolInvocations: true` 的混合工具上下文，需支持此功能的 Gemini 3 模型 |

玩家仍从会话菜单提问，例如“搜索 Minecraft 最新版本的更新内容”。三家返回的搜索引用统一显示为 `[1]` 等编号和可点击来源；引用插入时保留原文，Gemini 的 UTF-8 字节位置转换为 Java 字符位置，支持中文。来源标题和 URL 一同保存到玩家会话中。搜索和回复沿用异步及实体调度器，兼容 Paper 和 Folia。

普通 Chat Completions 模型不自动获得搜索能力，不支持时关闭搜索开关；需要 OpenAI 官方搜索与函数工具时可使用 `openai-responses` 及支持相应工具的模型。Gemini 2.5 可以分别使用搜索或函数调用，不支持本实现的混合工具上下文时关闭其中一个开关。其他网关必须支持所选格式的搜索参数和工具组合，不支持时执行 `/helpme reload` 前调整配置。已有自定义系统提示词不会被覆盖，可自行加入“涉及最新资料时先联网搜索并引用来源”。

每日 credits 按原生接口返回的 token 用量和配置费率结算。搜索新增输入无法提前完整预知，实际用量超过预留时会完整记录并阻止后续请求；搜索工具的额外服务费用不属于 credits。官方参考：[OpenAI 搜索](https://developers.openai.com/api/docs/guides/tools-web-search)、[Anthropic 搜索](https://platform.claude.com/docs/en/agents-and-tools/tool-use/web-search-tool)、[Gemini 搜索](https://ai.google.dev/gemini-api/docs/generate-content/google-search)、[Gemini 混合工具](https://ai.google.dev/gemini-api/docs/generate-content/tool-combination)。

## 服务器插件列表

`ai.plugin-list.enabled` 默认开启，AI 可以按需调用 `get_server_plugins`，获取服务器已加载插件的名称、版本和启用状态（包括未启用的已加载插件）。例如 `/helpme 服务器装了哪些插件？`。列表不会在每次提问时主动发送，也不会包含插件配置、API Key 或文件内容；使用 AI 助手的玩家均可通过此工具查询列表。可设置为 `false` 并执行 `/helpme reload` 禁用。

四种 provider 都支持 `get_server_plugins` 函数调用，所选模型和网关须支持对应协议的函数工具。分别使用 Chat Completions 的 `tool_calls`/`tool` 消息、Responses 的 `function_call`/`function_call_output`、Anthropic 的 `tool_use`/`tool_result`、Gemini 的 `functionCall`/`functionResponse`。回传完整的推理、搜索结果、调用 ID 和签名等当前轮上下文；Gemini 保留所有返回 parts，Anthropic 搜索暂停时原样续接。每轮问题最多提供一次插件列表，之后移除该函数但保留搜索工具；未知函数不执行。每次提问最多进行四次生成请求（初次及最多三次续接），达到续接上限仍未完成时提示重试；`max-output-tokens` 和超时按每次 API 请求生效。

每次生成请求分别计数、预留和结算每日 credits 额度，后续输入包含工具结果并检查上下文上限。后续请求额度不足时停止回答，已完成请求的 credits 仍计入用量。最终回答保存到玩家会话；原始工具和推理项仅用于当前提问，不持久化。接口参考：[OpenAI 函数调用](https://developers.openai.com/api/docs/guides/function-calling)。

## 每日额度与上下文

默认全服每日额度为 5,000,000 credits，每个玩家为 100,000 credits，上下文上限为 100,000 token。这三个上限都必须是正整数，旧配置未填写时自动采用默认值。额度对所有玩家生效，包括 OP。上下文长度与 API 输出上限继续按 token 计算。

`ai.credits-per-token.input`、`output`、`cached-input` 分别配置每个普通输入、输出、缓存读取 token 消耗的 credits，默认是 4、12、1，允许非负整数（0 为免费）。实际费用为 `(input_tokens - cached_tokens) × input + output_tokens × output + cached_tokens × cached-input`，统一计数后不重复收取普通输入费；缓存字段缺失时按 0 处理。Responses 读取 `usage.input_tokens_details.cached_tokens`，Chat Completions 读取 `usage.prompt_tokens_details.cached_tokens`；Anthropic 输入为 `input_tokens + cache_creation_input_tokens + cache_read_input_tokens`，缓存读取部分使用 `cache_read_input_tokens`，缓存写入按普通输入计费。Gemini 输入为 `promptTokenCount + toolUsePromptTokenCount`，输出为 `candidatesTokenCount + thoughtsTokenCount`，缓存为 `cachedContentTokenCount`。输出包含思考 token，历史问答重新发送也按接口返回的输入用量计费。字段参考：[OpenAI Responses API](https://developers.openai.com/api/reference/cli/resources/responses/methods/create)。

每日按 `daily-reset-timezone` 指定时区的午夜重置，默认北京时间。请求发起前，Responses 使用 `/responses/input_tokens`、Anthropic 在不开搜索时使用 `/messages/count_tokens`、Gemini 使用 `/models/{model}:countTokens` 计数（包含系统提示词和工具定义）；Chat Completions 没有原生计数接口，Anthropic 计数接口不接受服务器搜索工具，因此这两种场景使用输入 JSON 的 UTF-8 字节数加 1024 token 余量估算。按输入 token × 输入与缓存读取费率中的较高值 + `max-output-tokens` × 输出费率预留 credits，以避免并发请求一起用掉剩余额度；请求前不假定命中缓存，完成后按实际用量退还多预留部分。剩余额度不足以覆盖预留量时拒绝提问。管理员赠送增加玩家当天个人上限，不增加全服上限，实际消耗仍同时计入个人和全服用量。

原生计数接口返回 404、405 或 501 时，同样使用输入 JSON 字节数加余量估算，包含工具定义和结果。计数接口其他错误会停止请求。估算可能比真实用量大，因而需要更多预留额度。生成接口没有返回完整的原生输入/输出用量、发生超时或结果无法解析时保留预留 credits；明确 HTTP 拒绝的请求退还预留 credits。估算无法保证任意第三方模型的精确计数：如实际返回用量超过预留，会完整记录实际 credits 用量并阻止后续请求。

credits 用量和每日赠送额度自动保存到 `plugins/AzurelandPlugin/credit-usage.yml`，重启和 `/helpme reload` 不清零。每次 API 生成请求记在自身发起当天，即使跨午夜完成；进程中断时未结算请求保留预留 credits。用量文件无法读取或写入时停止新请求、赠送、发放和使用重置卡，避免意外绕过额度。热重载后的新请求采用新费率和上限，已经提交的请求仍使用提交时配置结算。

上下文保存每位玩家每个会话的问答文本，互相隔离；同一玩家的所有会话共用每日 credits 额度、提问冷却和单请求限制。请求前若当前会话历史加本次问题和输出预算超过上限，自动丢弃该会话的旧对话并用当前问题重新开始；请求后若实际 token 用量超过上限，清空该会话上下文。自动清空时会提示玩家，系统提示词始终保留，其他会话不受影响。单独的问题加系统提示词仍然过大时会拒绝请求。

所有命名会话按玩家 UUID 持久化到 `plugins/AzurelandPlugin/conversations/<UUID>.json`，创建、删除、清空和请求结束时在异步线程原子保存，玩家退出、`/helpme reload`、插件或服务器重启均不清空历史。旧版单会话文件读取时自动作为“历史会话”保留，下次保存写入多会话格式；旧版的选中状态字段会被忽略，已有命名会话和问答保持不变。玩家在请求期间退出时，服务返回的回答仍保存到提交时的会话中。配置重载后，下次提问会使用新的模型和系统提示词，并保留旧问答；降低上下文上限后，在下次提问时检查并自动清空当前会话的超限历史。已完成并保存的会话可恢复，插件关闭时仍未完成的请求不在重启后继续执行。

计数参考：[OpenAI token 计数](https://developers.openai.com/api/docs/guides/token-counting)、[对话上下文](https://developers.openai.com/api/docs/guides/conversation-state)。

## Dialog 默认字体字宽数据

`src/main/resources/dialog-font-metrics.bin.gz` 仅包含从 Minecraft Java 26.3 官方默认字体计算的数值字宽及粗体偏移，不打包字体图片。构建和运行插件不需要下载字体或安装 Python 依赖。

如需重新生成该数据，可用 `scripts/generate_dialog_font_metrics.py`，传入官方 `client.jar`、资源中的 `minecraft/font/include/unifont.json`、`minecraft/font/unifont.zip` 和输出路径。该维护脚本需要 Python 和 Pillow；字体版本资料来自 [Mojang 官方版本清单](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json)。
