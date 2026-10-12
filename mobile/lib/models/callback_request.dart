/// Заявка «Перезвоните мне» с сайта (CallbackRequestApiController).
class CallbackRequest {
  final int id;
  final String? name;
  final String phone;
  final String? business;
  final String status;
  final String? note;
  final DateTime createdAt;
  final DateTime? handledAt;
  final String? handledBy;

  CallbackRequest({
    required this.id,
    this.name,
    required this.phone,
    this.business,
    required this.status,
    this.note,
    required this.createdAt,
    this.handledAt,
    this.handledBy,
  });

  factory CallbackRequest.fromJson(Map<String, dynamic> json) => CallbackRequest(
        id: json['id'] as int,
        name: json['name'] as String?,
        phone: json['phone'] as String,
        business: json['business'] as String?,
        status: json['status'] as String,
        note: json['note'] as String?,
        createdAt: DateTime.parse(json['createdAt'] as String),
        handledAt: json['handledAt'] == null ? null : DateTime.parse(json['handledAt'] as String),
        handledBy: json['handledBy'] as String?,
      );
}

const callbackStatuses = {
  'NEW': 'Новая',
  'CONTACTED': 'Связались',
  'ORDERED': 'Заказал',
  'REJECTED': 'Отказ',
};
