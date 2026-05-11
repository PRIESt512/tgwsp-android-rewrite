package soft.shadlv.twp_rewritekts.services

class LocalTGProxyService : TGProxyServiceBase(){
    override val notificationId: Int
        get() = 1
    override val proxyType: ProxyType
        get() = ProxyType.LOCAL
}