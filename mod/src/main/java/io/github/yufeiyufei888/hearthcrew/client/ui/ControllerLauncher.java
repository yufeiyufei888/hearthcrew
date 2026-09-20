package io.github.yufeiyufei888.hearthcrew.client.ui;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
/** Fixed installed local launcher. No command text or path comes from a server, model, or UI field. */
public final class ControllerLauncher {
 private static final AtomicBoolean starting=new AtomicBoolean();
 private static Path script(){String local=System.getenv("LOCALAPPDATA");return local==null?null:Path.of(local,"HearthCrew","start-installed-controller.ps1").toAbsolutePath().normalize();}
 private static void notice(String text){net.minecraft.client.Minecraft.getInstance().execute(()->ClientUi.setNotice(text));}
 public static void start(){
  Path script=script();if(script==null||!Files.isRegularFile(script)){notice("启动入口尚未安装，请运行项目安装脚本");return;}
  if(!starting.compareAndSet(false,true)){notice("控制器正在启动，请稍候");return;}
  notice("正在启动本机控制器；登录状态沿用专用配置");
  CompletableFuture.runAsync(()->{try{
   var process=new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-WindowStyle","Hidden","-File",script.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
   if(!process.waitFor(60,TimeUnit.SECONDS))notice("启动等待超时，请查看控制器诊断日志");
   else notice(process.exitValue()==0?"启动脚本已完成，请刷新连接状态":"启动失败，请查看控制器启动日志");
  }catch(Exception error){notice("启动失败："+error.getClass().getSimpleName());}finally{starting.set(false);}});
 }
}
