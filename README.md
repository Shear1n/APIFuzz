# APIFuzz
Burpsuite plug-in, multi-site resource crawling, supports deep crawling interface

## 功能介绍

1、默认爬取网站所有加载接口，点击查看提取结果，可查看当前加载网站所有静态资源以及接口列表
2、支持结果导出

## 使用方法

Burpsuite直接导入jar文件，代理设置完毕后直接访问网站，插件页面会显示加载当前资源

e.g.

访问`https://www.baidu.com`，查看提取结果，这里随意查询一条，以第7条为例
<img width="1260" alt="image-20250604171314925" src="https://github.com/user-attachments/assets/e084477b-9c89-4b76-8913-70a36840ac76" />
点击第7条查看提取结果
<img width="800" alt="image-20250604171237913" src="https://github.com/user-attachments/assets/96e6db11-04a8-41b8-80c4-e8063bd33ef6" />
然后勾选想要爬取的内容，点击爬取选中内容，此时资源列表会将这一条列出来
<img width="1239" alt="image-20250604171519085" src="https://github.com/user-attachments/assets/e907fb4c-d436-4498-9c72-55cfa97816c7" />
再接着点击查看提取结果，会爬取当前/passApi/js/login.js中所有的资源接口。（双击链接地址可进行复制资源）
<img width="800" alt="image-20250604171212557" src="https://github.com/user-attachments/assets/9750500d-5f4a-4541-91d7-b58a4b1f8a74" />

## 后记
随手做的，肯定会有很多问题，后续可能会慢慢完善
