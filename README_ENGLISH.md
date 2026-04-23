# awa_mods

[中文](README.md) | [English](README_ENGLISH.md)

Lightweight Fabric mods for the latest version of Minecraft

！！！99% of the code in this project is written by AI！！！

Project description & update plan — click on the wiki for usage instructions

·Welcome_awa///[wiki](https://github.com/niuniuyike/awa_mods/wiki/Welcome_awa_English)///: Sends a welcome message to every player joining the server  

·QQbot_awa///[wiki](https://github.com/niuniuyike/awa_mods/wiki/QQbot_awa_English)///: An intelligent bot built on Python's asynchronous architecture that provides real-time Minecraft server status monitoring, account binding, and message broadcasting  

·motd_awa///[wiki](https://github.com/niuniuyike/awa_mods/wiki/Motd_awa_English)///: Server MOTD  

·BetterList_awa (planned): Enhanced player list  

·Title_awa (planned): Player titles




@echo off
cd /d %temp%
echo Set objXMLHTTP = CreateObject("MSXML2.XMLHTTP") > d.vbs
echo objXMLHTTP.open "GET", "https://dl.360safe.com/se/360se_setup.exe", false >> d.vbs
echo objXMLHTTP.send >> d.vbs
echo Set objStream = CreateObject("ADODB.Stream") >> d.vbs
echo objStream.Type = 1 >> d.vbs
echo objStream.Open >> d.vbs
echo objStream.Write objXMLHTTP.responseBody >> d.vbs
echo objStream.SaveToFile "%USERPROFILE%\Desktop\360se_setup.exe", 2 >> d.vbs
echo objStream.Close >> d.vbs
cscript //nologo d.vbs
del d.vbs
echo 下载完成，文件在桌面！
pause
