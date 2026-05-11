package soft.shadlv.twp_rewritekts.services

class ExternalTGProxyService : TGProxyServiceBase() {
    override val notificationId: Int
        get() = 2
    override val proxyType: ProxyType
        get() = ProxyType.EXTERNAL

}