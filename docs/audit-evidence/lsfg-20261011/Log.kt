package timber.log
object Timber { fun tag(tag:String)=this; fun i(format:String,vararg args:Any?){};fun d(format:String,vararg args:Any?){};fun w(format:String,vararg args:Any?){};fun e(error:Throwable,format:String,vararg args:Any?){} }
