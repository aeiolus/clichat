// wmchat.h
//
// Copyright (c) 2020-2024 Kristofer Berggren
// All rights reserved.
//
// nchat is distributed under the MIT license, see LICENSE for details.

#pragma once

#include <condition_variable>
#include <deque>
#include <map>
#include <thread>

#include "config.h"
#include "protocol.h"

class WmChat : public Protocol
{
public:
  WmChat();
  virtual ~WmChat();
  static std::string GetName() { return "WhatsAppMd"; }
  static std::string GetLibName() { return "libwmchat"; }
  static std::string GetCreateFunc() { return "CreateWmChat"; }
  static std::string GetSetupMessage()
  {
#if defined(__APPLE__) || defined(__GLIBC__)
    return "";
#else
    return "\nUNSUPPORTED PLATFORM:\nThe WhatsApp protocol implementation officially only supports glibc on Linux.\n"
           "For details, refer to https://github.com/d99kris/nchat/issues/204\n";
#endif
  }

  std::string GetProfileId() const;
  std::string GetProfileDisplayName() const;
  bool HasFeature(ProtocolFeature p_ProtocolFeature) const;

  bool SetupProfile(const std::string& p_ProfilesDir, std::string& p_ProfileId);
  bool LoadProfile(const std::string& p_ProfilesDir, const std::string& p_ProfileId);
  bool CloseProfile();

  bool Login();
  bool Logout();

  void Process();

  void SendRequest(std::shared_ptr<RequestMessage> p_RequestMessage);
  void SetMessageHandler(const std::function<void(std::shared_ptr<ServiceMessage>)>& p_MessageHandler);

public:
  static void AddInstance(int p_ConnId, WmChat* p_Instance);
  static void RemoveInstance(int p_ConnId);
  static WmChat* GetInstance(int p_ConnId);

private:
  void Init();
  void Cleanup();
  void CallMessageHandler(std::shared_ptr<ServiceMessage> p_ServiceMessage);
  void PerformRequest(std::shared_ptr<RequestMessage> p_RequestMessage);
  std::string GetProxyUrl() const;

private:
  std::string m_ProfileId;
  std::function<void(std::shared_ptr<ServiceMessage>)> m_MessageHandler;

  bool m_Running = false;
  std::thread m_Thread;
  std::deque<std::shared_ptr<RequestMessage>> m_RequestsQueue;
  std::mutex m_ProcessMutex;
  std::condition_variable m_ProcessCondVar;

  static std::mutex s_ConnIdMapMutex;
  static std::map<int, WmChat*> s_ConnIdMap;
  int m_ConnId = -1;
  std::string m_ProfileDir;
  Config m_Config;
  int m_WhatsmeowDate = 0;
  int m_ProfileDirVersion = 0;
  bool m_WasOnline = false;
  static const int s_CacheDirVersion = 0;
};

extern "C" {
void WmNewContactsNotify(int p_ConnId, char* p_ChatId, char* p_Name, char* p_Phone, int p_IsSelf);
void WmNewChatsNotify(int p_ConnId, char* p_ChatId, int p_IsUnread, int p_IsMuted, int p_LastMessageTime);
void WmNewMessagesNotify(int p_ConnId, char* p_ChatId, char* p_MsgId, char* p_SenderId, char* p_Text, int p_FromMe,
                         char* p_ReplyId, char* p_FileId, char* p_FilePath, int p_FileStatus, int p_TimeSent,
                         int p_IsRead);
void WmNewStatusNotify(int p_ConnId, char* p_ChatId, char* p_UserId, int p_IsOnline, int p_IsTyping, int p_TimeSeen);
void WmNewMessageStatusNotify(int p_ConnId, char* p_ChatId, char* p_MsgId, int p_IsRead);
void WmNewMessageFileNotify(int p_ConnId, char* p_ChatId, char* p_MsgId, char* p_FilePath, int p_FileStatus,
                            int p_Action);
void WmDeleteChatNotify(int p_ConnId, char* p_ChatId);
void WmUpdateMuteNotify(int p_ConnId, char* p_ChatId, int p_IsMuted);
void WmReinit(int p_ConnId);
void WmSetStatus(int p_Flags);
void WmClearStatus(int p_Flags);
void WmLogTrace(char* p_Filename, int p_LineNo, char* p_Message);
void WmLogDebug(char* p_Filename, int p_LineNo, char* p_Message);
void WmLogInfo(char* p_Filename, int p_LineNo, char* p_Message);
void WmLogWarning(char* p_Filename, int p_LineNo, char* p_Message);
void WmLogError(char* p_Filename, int p_LineNo, char* p_Message);
}

extern "C" WmChat* CreateWmChat();
