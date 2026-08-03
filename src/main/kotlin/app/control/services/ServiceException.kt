package app.control.services

/** 服务层错误：code 必须是可枚举原因码或稳定的机读字符串。 */
class ServiceException(
    val code: String,
    val httpStatus: Int,
    override val message: String,
) : RuntimeException(message)
