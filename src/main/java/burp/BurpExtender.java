package burp;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.net.URL;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BurpExtender implements IBurpExtender, IHttpListener, ITab, IContextMenuFactory {
    private IBurpExtenderCallbacks callbacks;
    private IExtensionHelpers helpers;
    private JPanel mainPanel;
    private JTable resourceTable;
    private DefaultTableModel resourceTableModel;
    private JTextArea detailArea;

    // 使用Map存储每个域名的上下文
    private final Map<String, DomainContext> domainContextMap = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);

    // 新增变量：存储当前选择的资源和提取结果
    private ResourceEntry currentSelectedResource = null;
    private List<ExtractedLink> currentExtractedLinks = Collections.synchronizedList(new ArrayList<>());
    private JDialog previewDialog;

    // 颜色方案
    private final Color backgroundColor = new Color(45, 45, 48);
    private final Color foregroundColor = new Color(241, 241, 241);
    private final Color buttonColor = new Color(0, 122, 204);
    private final Color tableHeaderColor = new Color(30, 30, 32);
    private final Color evenRowColor = new Color(50, 50, 52);
    private final Color oddRowColor = new Color(55, 55, 57);
    private final Color selectedRowColor = new Color(62, 62, 64);
    private final Color crawlButtonColor = new Color(46, 125, 50);

    @Override
    public void registerExtenderCallbacks(IBurpExtenderCallbacks callbacks) {
        this.callbacks = callbacks;
        this.helpers = callbacks.getHelpers();
        callbacks.setExtensionName("API Resource Crawler 1.0");

        // 注册上下文菜单
        callbacks.registerContextMenuFactory(this);

        initUI();
        callbacks.customizeUiComponent(mainPanel);
        callbacks.addSuiteTab(this);
        callbacks.registerHttpListener(this);
    }

    private void initUI() {
        mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        mainPanel.setBackground(backgroundColor);

        // 创建表格模型 - 添加域名列
        resourceTableModel = new DefaultTableModel(new Object[]{"#", "域名", "URL", "类型", "状态", "大小", "查看提取结果"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 6; // 只有操作列可编辑（按钮）
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == 0 || columnIndex == 4) return Integer.class; // 序号和状态码是整数
                if (columnIndex == 5) return Long.class; // 响应大小是长整型
                return String.class;
            }
        };

        resourceTable = new JTable(resourceTableModel) {
            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
                Component c = super.prepareRenderer(renderer, row, column);

                // 设置行颜色
                if (isRowSelected(row)) {
                    c.setBackground(selectedRowColor);
                } else {
                    c.setBackground(row % 2 == 0 ? evenRowColor : oddRowColor);
                }
                c.setForeground(foregroundColor);

                // 序号居中
                if (column == 0 || column == 4 || column == 5) {
                    ((JLabel) c).setHorizontalAlignment(SwingConstants.CENTER);
                }

                return c;
            }
        };

        // 设置表格样式
        resourceTable.setAutoCreateRowSorter(true);
        resourceTable.setFillsViewportHeight(true);
        resourceTable.setRowHeight(25);
        resourceTable.setShowGrid(false);
        resourceTable.setIntercellSpacing(new Dimension(0, 0));
        resourceTable.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        resourceTable.setForeground(foregroundColor);
        resourceTable.setBackground(backgroundColor);
        resourceTable.setSelectionBackground(selectedRowColor);
        resourceTable.setSelectionForeground(Color.WHITE);

        // 设置表头样式
        JTableHeader header = resourceTable.getTableHeader();
        header.setBackground(tableHeaderColor);
        header.setForeground(new Color(200, 200, 200));
        header.setFont(new Font("Segoe UI", Font.BOLD, 12));
        header.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));

        // 设置列宽
        resourceTable.getColumnModel().getColumn(0).setPreferredWidth(40);  // 序号列
        resourceTable.getColumnModel().getColumn(1).setPreferredWidth(150); // 域名列
        resourceTable.getColumnModel().getColumn(2).setPreferredWidth(400); // URL列
        resourceTable.getColumnModel().getColumn(3).setPreferredWidth(80);  // 类型列
        resourceTable.getColumnModel().getColumn(4).setPreferredWidth(60);  // 状态列
        resourceTable.getColumnModel().getColumn(5).setPreferredWidth(80);  // 大小列
        resourceTable.getColumnModel().getColumn(6).setPreferredWidth(120); // 操作列

        // 操作列渲染器和编辑器
        resourceTable.getColumnModel().getColumn(6).setCellRenderer(new ButtonRenderer());
        resourceTable.getColumnModel().getColumn(6).setCellEditor(new ButtonEditor(new JCheckBox(), this));

        JScrollPane tableScroll = new JScrollPane(resourceTable);
        tableScroll.setBorder(BorderFactory.createTitledBorder("爬取资源列表 (多网站支持)"));
        tableScroll.getViewport().setBackground(backgroundColor);

        detailArea = new JTextArea();
        detailArea.setEditable(false);
        detailArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        detailArea.setForeground(new Color(220, 220, 220));
        detailArea.setBackground(new Color(40, 40, 42));
        detailArea.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JScrollPane detailScroll = new JScrollPane(detailArea);
        detailScroll.setBorder(BorderFactory.createTitledBorder("爬取结果"));

        // 按钮面板
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        buttonPanel.setBackground(backgroundColor);

        JButton clearButton = createStyledButton("清除结果", new Color(120, 120, 120));
        clearButton.addActionListener(e -> clearResults());

        JButton exportButton = createStyledButton("导出结果", new Color(46, 125, 50));
        exportButton.addActionListener(e -> exportResults());

        JButton autoCrawlToggle = createStyledButton("自动爬取: ON", new Color(70, 130, 180));
        autoCrawlToggle.addActionListener(e -> {
            String text = autoCrawlToggle.getText();
            if (text.contains("ON")) {
                autoCrawlToggle.setText("自动爬取: OFF");
                autoCrawlToggle.setBackground(new Color(180, 70, 70));
            } else {
                autoCrawlToggle.setText("自动爬取: ON");
                autoCrawlToggle.setBackground(new Color(70, 130, 180));
            }
        });

        buttonPanel.add(clearButton);
        buttonPanel.add(exportButton);
        buttonPanel.add(autoCrawlToggle);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBackground(backgroundColor);
        bottomPanel.add(buttonPanel, BorderLayout.NORTH);
        bottomPanel.add(detailScroll, BorderLayout.CENTER);

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, bottomPanel);
        splitPane.setDividerLocation(400);
        splitPane.setResizeWeight(0.6);
        splitPane.setBorder(BorderFactory.createEmptyBorder());

        mainPanel.add(splitPane, BorderLayout.CENTER);
    }

    private JButton createStyledButton(String text, Color bgColor) {
        JButton button = new JButton(text);
        button.setFont(new Font("Segoe UI", Font.BOLD, 12));
        button.setBackground(bgColor);
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(100, 100, 100)),
                BorderFactory.createEmptyBorder(5, 15, 5, 15)
        ));
        return button;
    }

    // 替代String.repeat()的方法
    private String repeatString(String str, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(str);
        }
        return sb.toString();
    }

    private void clearResults() {
        int confirm = JOptionPane.showConfirmDialog(mainPanel,
                "确定要清除所有结果吗？",
                "确认清除",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);

        if (confirm == JOptionPane.YES_OPTION) {
            domainContextMap.clear();
            resourceTableModel.setRowCount(0);
            detailArea.setText("");
        }
    }

    private void exportResults() {
        if (resourceTableModel.getRowCount() == 0) {
            JOptionPane.showMessageDialog(mainPanel,
                    "没有可导出的结果",
                    "提示",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("#,域名,URL,类型,状态,大小\n");

        for (int i = 0; i < resourceTableModel.getRowCount(); i++) {
            sb.append(i + 1).append(",")
                    .append(resourceTableModel.getValueAt(i, 1)).append(",")
                    .append(resourceTableModel.getValueAt(i, 2)).append(",")
                    .append(resourceTableModel.getValueAt(i, 3)).append(",")
                    .append(resourceTableModel.getValueAt(i, 4)).append(",")
                    .append(resourceTableModel.getValueAt(i, 5)).append("\n");
        }

        callbacks.saveExtensionSetting("exported_results", sb.toString());
        JOptionPane.showMessageDialog(mainPanel,
                "结果已导出到Burp扩展设置中",
                "导出成功",
                JOptionPane.INFORMATION_MESSAGE);
    }

    public void extractDetailsForUrl(String url) {
        for (int i = 0; i < resourceTableModel.getRowCount(); i++) {
            if (resourceTableModel.getValueAt(i, 2).equals(url)) {
                String domain = (String) resourceTableModel.getValueAt(i, 1);
                for (ResourceEntry entry : domainContextMap.get(domain).resourceList) {
                    if (entry.url.equals(url)) {
                        currentSelectedResource = entry;
                        createPreviewDialog();
                        return;
                    }
                }
            }
        }
    }

    private void createPreviewDialog() {
        if (previewDialog != null && previewDialog.isVisible()) {
            previewDialog.dispose();
        }

        previewDialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(mainPanel),
                "提取结果预览 - " + currentSelectedResource.url,
                true);
        previewDialog.setSize(800, 600);
        previewDialog.setLayout(new BorderLayout());
        previewDialog.setLocationRelativeTo(mainPanel);
        previewDialog.getContentPane().setBackground(backgroundColor);

        // 创建预览表格模型
        DefaultTableModel previewModel = new DefaultTableModel(new Object[]{"选择", "链接", "类型"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 0 ? Boolean.class : String.class;
            }
        };

        // 提取链接并分类
        currentExtractedLinks.clear();
        for (String link : currentSelectedResource.extractedLinks) {
            ExtractedLink el = new ExtractedLink();
            el.url = link;
            el.type = isApiPath(link) ? "API" : "资源";
            currentExtractedLinks.add(el);
            previewModel.addRow(new Object[]{false, el.url, el.type});
        }

        JTable previewTable = new JTable(previewModel) {
            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
                Component c = super.prepareRenderer(renderer, row, column);
                c.setBackground(row % 2 == 0 ? evenRowColor : oddRowColor);
                c.setForeground(foregroundColor);
                return c;
            }
        };

        // 设置表格样式
        previewTable.setRowHeight(25);
        previewTable.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        previewTable.setBackground(backgroundColor);
        previewTable.setForeground(foregroundColor);
        previewTable.setSelectionBackground(selectedRowColor);
        previewTable.setSelectionForeground(Color.WHITE);
        previewTable.setGridColor(new Color(70, 70, 70));

        // 设置表头样式
        JTableHeader previewHeader = previewTable.getTableHeader();
        previewHeader.setBackground(tableHeaderColor);
        previewHeader.setForeground(new Color(200, 200, 200));
        previewHeader.setFont(new Font("Segoe UI", Font.BOLD, 12));

        // 设置列宽
        previewTable.getColumnModel().getColumn(0).setPreferredWidth(60);
        previewTable.getColumnModel().getColumn(1).setPreferredWidth(500);
        previewTable.getColumnModel().getColumn(2).setPreferredWidth(80);

        JScrollPane previewScroll = new JScrollPane(previewTable);
        previewScroll.setBorder(BorderFactory.createEmptyBorder());
        previewScroll.getViewport().setBackground(backgroundColor);

        // 添加标题信息
        JLabel titleLabel = new JLabel("资源: " + currentSelectedResource.url);
        titleLabel.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
        titleLabel.setForeground(new Color(220, 220, 255));
        titleLabel.setBackground(new Color(60, 60, 65));
        titleLabel.setOpaque(true);

        previewDialog.add(titleLabel, BorderLayout.NORTH);
        previewDialog.add(previewScroll, BorderLayout.CENTER);

        // 添加按钮面板
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        buttonPanel.setBackground(new Color(50, 50, 55));
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JButton selectAllButton = createStyledButton("全选", buttonColor);
        selectAllButton.addActionListener(e -> {
            for (int i = 0; i < previewModel.getRowCount(); i++) {
                previewModel.setValueAt(true, i, 0);
            }
        });

        JButton deselectAllButton = createStyledButton("取消全选", new Color(120, 120, 120));
        deselectAllButton.addActionListener(e -> {
            for (int i = 0; i < previewModel.getRowCount(); i++) {
                previewModel.setValueAt(false, i, 0);
            }
        });

        JButton crawlButton = createStyledButton("爬取选中内容", crawlButtonColor);
        crawlButton.addActionListener(e -> {
            List<String> selectedUrls = new ArrayList<>();
            for (int i = 0; i < previewModel.getRowCount(); i++) {
                if ((Boolean) previewModel.getValueAt(i, 0)) {
                    selectedUrls.add(currentExtractedLinks.get(i).url);
                }
            }

            if (selectedUrls.isEmpty()) {
                JOptionPane.showMessageDialog(previewDialog,
                        "请至少选择一个链接进行爬取",
                        "提示",
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }

            previewDialog.dispose();
            crawlSelectedLinks(selectedUrls);
        });

        buttonPanel.add(selectAllButton);
        buttonPanel.add(deselectAllButton);
        buttonPanel.add(crawlButton);

        previewDialog.add(buttonPanel, BorderLayout.SOUTH);
        previewDialog.setVisible(true);
    }

    private void crawlSelectedLinks(List<String> urls) {
        detailArea.setText("开始爬取 " + urls.size() + " 个链接...\n\n");

        executorService.submit(() -> {
            int total = urls.size();
            int completed = 0;

            for (String url : urls) {
                try {
                    // 显示爬取进度
                    final int current = completed + 1;
                    SwingUtilities.invokeLater(() -> {
                        detailArea.append(String.format("[%d/%d] 爬取: %s\n", current, total, url));
                        detailArea.setCaretPosition(detailArea.getDocument().getLength());
                    });

                    IHttpRequestResponse response = makeHttpRequest(url);
                    if (response != null) {
                        byte[] responseBytes = response.getResponse();
                        if (responseBytes != null && responseBytes.length > 0) {
                            IResponseInfo responseInfo = helpers.analyzeResponse(responseBytes);
                            int statusCode = responseInfo.getStatusCode();
                            String mimeType = responseInfo.getStatedMimeType().toLowerCase();
                            int bodyOffset = responseInfo.getBodyOffset();
                            String body = new String(responseBytes).substring(bodyOffset);

                            // 添加到详情区域
                            SwingUtilities.invokeLater(() -> {
                                String separator = repeatString("=", 100); // 使用替代方法
                                String result = String.format(
                                        "【URL】 %s\n" +
                                                "【状态】 %d\n" +
                                                "【类型】 %s\n" +
                                                "【大小】 %s\n" +
                                                "【预览】\n%s\n\n%s\n\n",
                                        url, statusCode, mimeType, formatSize(body.length()),
                                        getPreviewContent(body, 300),
                                        separator
                                );
                                detailArea.append(result);
                                detailArea.setCaretPosition(detailArea.getDocument().getLength());
                            });
                        } else {
                            SwingUtilities.invokeLater(() -> {
                                detailArea.append("【错误】无响应内容: " + url + "\n\n");
                                detailArea.setCaretPosition(detailArea.getDocument().getLength());
                            });
                        }
                    } else {
                        SwingUtilities.invokeLater(() -> {
                            detailArea.append("【错误】请求失败: " + url + "\n\n");
                            detailArea.setCaretPosition(detailArea.getDocument().getLength());
                        });
                    }
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        detailArea.append("【错误】爬取失败: " + url + " - " + ex.getMessage() + "\n\n");
                        detailArea.setCaretPosition(detailArea.getDocument().getLength());
                    });
                } finally {
                    completed++;
                }
            }

            SwingUtilities.invokeLater(() -> {
                detailArea.append("\n爬取完成! 共处理 " + urls.size() + " 个链接\n");
                detailArea.setCaretPosition(detailArea.getDocument().getLength());
            });
        });
    }

    private String getPreviewContent(String content, int maxLength) {
        if (content == null || content.isEmpty()) {
            return "[空内容]";
        }

        // 移除过多的空白字符
        content = content.replaceAll("\\s+", " ");

        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "... [内容已截断，完整长度: " + content.length() + " 字符]";
    }

    private boolean isApiPath(String url) {
        // API路径通常没有文件扩展名
        if (url.contains(".js") || url.contains(".css") || url.contains(".png") ||
                url.contains(".jpg") || url.contains(".gif") || url.contains(".ico")) {
            return false;
        }

        // API路径通常包含多个路径段
        String path = getPathFromUrl(url);
        if (path == null) return false;

        // 计算路径段数量
        String[] segments = path.split("/");
        int segmentCount = 0;
        for (String seg : segments) {
            if (!seg.isEmpty()) segmentCount++;
        }

        // 至少包含2个路径段
        return segmentCount >= 2;
    }

    private String getPathFromUrl(String url) {
        try {
            URL u = new URL(url);
            return u.getPath();
        } catch (Exception e) {
            return null;
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp-1);
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    @Override
    public void processHttpMessage(int toolFlag, boolean messageIsRequest, IHttpRequestResponse messageInfo) {
        if (messageIsRequest) return;

        URL url = helpers.analyzeRequest(messageInfo).getUrl();
        String urlStr = url.toString();
        String domainKey = getBaseDomain(url);

        // 获取或创建域名的上下文
        DomainContext context = domainContextMap.computeIfAbsent(domainKey, k -> {
            callbacks.printOutput("创建新域名上下文: " + domainKey);
            return new DomainContext();
        });

        // 跳过已处理的URL
        if (context.loadedUrls.contains(urlStr)) return;
        context.loadedUrls.add(urlStr);

        byte[] responseBytes = messageInfo.getResponse();
        if (responseBytes == null || responseBytes.length == 0) return;

        IResponseInfo responseInfo = helpers.analyzeResponse(responseBytes);
        int statusCode = responseInfo.getStatusCode();
        String mimeType = responseInfo.getStatedMimeType().toLowerCase();
        int bodyOffset = responseInfo.getBodyOffset();
        long responseSize = responseBytes.length;
        String body = new String(responseBytes).substring(bodyOffset);

        // 扩展处理范围：包含所有文本类型资源
        if (isProcessableMimeType(mimeType)) {
            ResourceEntry entry = new ResourceEntry();
            entry.url = urlStr;
            entry.type = mimeType;
            entry.status = statusCode;
            entry.size = responseSize;
            entry.domain = domainKey;
            entry.extractedLinks = extractLinksFromBody(body, urlStr, mimeType);

            // 添加到域名的资源列表
            context.resourceList.add(entry);

            SwingUtilities.invokeLater(() -> {
                resourceTableModel.addRow(new Object[]{
                        resourceTableModel.getRowCount() + 1,
                        entry.domain,
                        entry.url,
                        entry.type,
                        entry.status,
                        entry.size,
                        "查看提取结果"
                });
            });
        }
    }

    private boolean isProcessableMimeType(String mimeType) {
        return mimeType.contains("html") || mimeType.contains("script") ||
                mimeType.contains("json") || mimeType.contains("text") ||
                mimeType.contains("css") || mimeType.contains("xml") ||
                mimeType.contains("javascript");
    }

    private IHttpRequestResponse makeHttpRequest(String urlStr) {
        try {
            URL targetUrl = new URL(urlStr);
            IHttpService service = helpers.buildHttpService(
                    targetUrl.getHost(),
                    targetUrl.getPort(),
                    targetUrl.getProtocol()
            );

            byte[] request = helpers.buildHttpRequest(targetUrl);
            return callbacks.makeHttpRequest(service, request);
        } catch (Exception ex) {
            callbacks.printError("创建请求失败: " + urlStr + " - " + ex.getMessage());
            return null;
        }
    }

    private List<String> extractLinksFromBody(String body, String baseUrl, String mimeType) {
        Set<String> uniqueLinks = new HashSet<>();

        // 1. 提取完整URL
        Pattern fullUrlPattern = Pattern.compile(
                "https?://[a-zA-Z0-9_\\-.:]+(?:/[a-zA-Z0-9_\\-.:]+)*",
                Pattern.CASE_INSENSITIVE
        );

        Matcher fullUrlMatcher = fullUrlPattern.matcher(body);
        while (fullUrlMatcher.find()) {
            String url = fullUrlMatcher.group();
            uniqueLinks.add(url);
        }

        // 2. 提取相对资源路径
        Pattern relPattern = Pattern.compile(
                // 匹配带引号的相对路径
                "[\"']([a-zA-Z0-9_\\-.:/]+?\\.(?:js|css|html?|json|woff2?|ttf|eot|png|jpg|jpeg|gif|svg|ico|webp))[\"']|" +
                        // 匹配不带引号的路径（常见于HTML）
                        "\\s+(src|href|data-src|data-href)\\s*=\\s*([a-zA-Z0-9_\\-.:/]+)",
                Pattern.CASE_INSENSITIVE
        );

        Matcher relMatcher = relPattern.matcher(body);
        while (relMatcher.find()) {
            String path = null;
            if (relMatcher.group(1) != null) {
                path = relMatcher.group(1);
            } else if (relMatcher.group(3) != null) {
                path = relMatcher.group(3);
            }

            if (path != null) {
                // 清理路径
                path = path.replaceAll("['\"]", "").trim();

                // 转换为完整URL
                String fullUrl = resolveRelativePath(path, baseUrl);
                if (fullUrl != null) {
                    uniqueLinks.add(fullUrl);
                }
            }
        }

        // 3. 提取API接口路径
        Pattern apiPattern = Pattern.compile(
                "[\"'](/[a-zA-Z0-9_\\-.:]+(?:/[a-zA-Z0-9_\\-.:]+)+)[\"']|" +
                        "[\"']([a-zA-Z0-9_\\-.:]+(?:/[a-zA-Z0-9_\\-.:]+)+)[\"']",
                Pattern.CASE_INSENSITIVE
        );

        Matcher apiMatcher = apiPattern.matcher(body);
        while (apiMatcher.find()) {
            // 先检查第一个分组（以/开头的路径）
            String apiPath = apiMatcher.group(1);
            if (apiPath == null) {
                // 检查第二个分组（不以/开头的路径）
                apiPath = apiMatcher.group(2);
            }

            if (apiPath != null) {
                // 转换为完整URL
                String fullUrl = resolveRelativePath(apiPath, baseUrl);
                if (fullUrl != null) {
                    uniqueLinks.add(fullUrl);
                }
            }
        }

        // 4. 提取JS动态加载的资源
        if (mimeType.contains("javascript") || mimeType.contains("html")) {
            // 匹配动态导入: import('module')
            Pattern dynamicImportPattern = Pattern.compile(
                    "import\\(['\"]([a-zA-Z0-9_\\-.:/]+)['\"]\\)",
                    Pattern.CASE_INSENSITIVE
            );

            Matcher importMatcher = dynamicImportPattern.matcher(body);
            while (importMatcher.find()) {
                String path = importMatcher.group(1);
                String fullUrl = resolveRelativePath(path, baseUrl);
                if (fullUrl != null) {
                    uniqueLinks.add(fullUrl);
                }
            }

            // 匹配require调用: require('module')
            Pattern requirePattern = Pattern.compile(
                    "require\\(['\"]([a-zA-Z0-9_\\-.:/]+)['\"]\\)",
                    Pattern.CASE_INSENSITIVE
            );

            Matcher requireMatcher = requirePattern.matcher(body);
            while (requireMatcher.find()) {
                String path = requireMatcher.group(1);
                String fullUrl = resolveRelativePath(path, baseUrl);
                if (fullUrl != null) {
                    uniqueLinks.add(fullUrl);
                }
            }

            // 匹配fetch/XMLHttpRequest调用
            Pattern fetchPattern = Pattern.compile(
                    "(?:fetch|open)\\s*\\(\\s*['\"]([a-zA-Z0-9_\\-.:/]+)['\"]",
                    Pattern.CASE_INSENSITIVE
            );

            Matcher fetchMatcher = fetchPattern.matcher(body);
            while (fetchMatcher.find()) {
                String path = fetchMatcher.group(1);
                String fullUrl = resolveRelativePath(path, baseUrl);
                if (fullUrl != null) {
                    uniqueLinks.add(fullUrl);
                }
            }
        }

        // 5. 提取CSS中的资源
        if (mimeType.contains("css")) {
            Pattern cssUrlPattern = Pattern.compile(
                    "url\\(\\s*['\"]?([a-zA-Z0-9_\\-.:/]+)['\"]?\\s*\\)",
                    Pattern.CASE_INSENSITIVE
            );

            Matcher cssMatcher = cssUrlPattern.matcher(body);
            while (cssMatcher.find()) {
                String path = cssMatcher.group(1);
                String fullUrl = resolveRelativePath(path, baseUrl);
                if (fullUrl != null) {
                    uniqueLinks.add(fullUrl);
                }
            }
        }

        return new ArrayList<>(uniqueLinks);
    }

    private String resolveRelativePath(String path, String baseUrl) {
        if (path == null || path.isEmpty()) return null;

        // 清理路径
        path = path.trim().replaceAll("['\"]", "");

        // 跳过数据URL和特殊协议
        if (path.startsWith("data:") || path.startsWith("javascript:") || path.startsWith("mailto:")) {
            return null;
        }

        try {
            // 如果已经是绝对URL
            if (path.startsWith("http://") || path.startsWith("https://")) {
                return path;
            }

            // 处理协议相对路径
            if (path.startsWith("//")) {
                return "http:" + path; // 默认使用http，实际会根据baseUrl协议处理
            }

            // 解析基础URL
            URL base = new URL(baseUrl);

            // 处理绝对路径
            if (path.startsWith("/")) {
                return new URL(base.getProtocol(), base.getHost(), base.getPort(), path).toString();
            }

            // 处理相对路径
            String basePath = base.getPath();
            if (basePath == null || basePath.isEmpty()) {
                basePath = "/";
            } else if (!basePath.endsWith("/")) {
                // 获取目录部分
                int lastSlash = basePath.lastIndexOf('/');
                if (lastSlash >= 0) {
                    basePath = basePath.substring(0, lastSlash + 1);
                }
            }

            // 构建完整路径
            String fullPath = basePath + path;

            // 规范化路径（处理../和./）
            fullPath = normalizePath(fullPath);

            return new URL(base.getProtocol(), base.getHost(), base.getPort(), fullPath).toString();
        } catch (Exception ex) {
            callbacks.printError("解析路径失败: " + path + " (base: " + baseUrl + ") - " + ex.getMessage());
            return null;
        }
    }

    private String normalizePath(String path) {
        if (path == null || path.isEmpty()) return path;

        // 处理./和../
        String[] parts = path.split("/");
        List<String> result = new ArrayList<>();

        for (String part : parts) {
            if (part.equals("..")) {
                if (!result.isEmpty()) {
                    result.remove(result.size() - 1);
                }
            } else if (!part.equals(".") && !part.isEmpty()) {
                result.add(part);
            }
        }

        return "/" + String.join("/", result);
    }

    private String getBaseDomain(URL url) {
        String protocol = url.getProtocol();
        String host = url.getHost();
        int port = url.getPort();

        if (port == -1) {
            return protocol + "://" + host;
        } else {
            return protocol + "://" + host + ":" + port;
        }
    }

    @Override
    public String getTabCaption() {
        return "APIFuzz";
    }

    @Override
    public Component getUiComponent() {
        return mainPanel;
    }

    @Override
    public java.util.List<JMenuItem> createMenuItems(IContextMenuInvocation invocation) {
        List<JMenuItem> menuItems = new ArrayList<>();

        // 获取当前选择的资源行
        int[] selectedRows = resourceTable.getSelectedRows();

        // 如果有选中的行，才显示菜单项
        if (selectedRows != null && selectedRows.length > 0) {
            JMenuItem viewExtractItem = new JMenuItem("查看提取结果");
            viewExtractItem.addActionListener(e -> {
                int modelRow = resourceTable.convertRowIndexToModel(selectedRows[0]);
                String url = (String) resourceTableModel.getValueAt(modelRow, 2);
                extractDetailsForUrl(url);
            });

            JMenuItem copyUrlItem = new JMenuItem("复制URL");
            copyUrlItem.addActionListener(e -> {
                // 只复制第一个选中的URL
                int modelRow = resourceTable.convertRowIndexToModel(selectedRows[0]);
                String url = (String) resourceTableModel.getValueAt(modelRow, 2);
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(url), null);
                callbacks.printOutput("已复制URL: " + url);
            });

            menuItems.add(viewExtractItem);
            menuItems.add(copyUrlItem);
        }

        return menuItems;
    }

    // 按钮渲染器
    static class ButtonRenderer extends JButton implements TableCellRenderer {
        public ButtonRenderer() {
            setOpaque(true);
        }

        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus, int row, int column) {
            setText((value == null) ? "" : value.toString());
            setBackground(new Color(0, 122, 204));
            setForeground(Color.WHITE);
            setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
            setFont(new Font("Segoe UI", Font.BOLD, 11));
            return this;
        }
    }

    // 按钮编辑器
    static class ButtonEditor extends DefaultCellEditor {
        private final JButton button;
        private String url;
        private BurpExtender extender;

        public ButtonEditor(JCheckBox checkBox, BurpExtender extender) {
            super(checkBox);
            this.extender = extender;
            button = new JButton();
            button.setOpaque(true);
            button.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    fireEditingStopped();
                    if (url != null) {
                        extender.extractDetailsForUrl(url);
                    }
                }
            });
        }

        public Component getTableCellEditorComponent(JTable table, Object value,
                                                     boolean isSelected, int row, int column) {
            url = (String) table.getModel().getValueAt(row, 2); // URL在第三列
            button.setText((value == null) ? "" : value.toString());
            return button;
        }

        public Object getCellEditorValue() {
            return button.getText();
        }
    }

    static class ResourceEntry {
        String domain;
        String url;
        String type;
        int status;
        long size;
        List<String> extractedLinks = new ArrayList<>();
    }

    static class ExtractedLink {
        String url;
        String type;
    }

    static class DomainContext {
        final Set<String> loadedUrls = Collections.synchronizedSet(new HashSet<>());
        final List<ResourceEntry> resourceList = Collections.synchronizedList(new ArrayList<>());
    }
}